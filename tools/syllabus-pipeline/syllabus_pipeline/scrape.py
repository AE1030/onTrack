"""Stage 1: pull section listings and full documents from Simple Syllabus into Postgres.

The site exposes two unauthenticated JSON endpoints:

    api2/doc-library-search    paginated listing of sections for the given term statuses
    api2/doc-full-page-get     the whole document for one section, by its code

Neither is documented, so both are treated as things that can change shape without notice.
A response that no longer carries `items` or `pagination` raises instead of quietly yielding
nothing, because "scraped 0 new documents" and "the API moved" look identical in a log and
only one of them is fine.
"""

from __future__ import annotations

import logging
import re
import time
from typing import Any, Iterable, Optional

import requests
from bs4 import BeautifulSoup

from .config import ScrapeConfig
from .store import Store

log = logging.getLogger(__name__)

# "BIOLOGY 3AA3", "SOCSCI 1SS3", "INDIGST 4T06B". Deliberately looser than the four-by-four
# pattern the original scraper used: that one returned None for every subject longer than
# four characters, which is most of them, and the summary step then silently re-derived the
# code with a different regex. One regex, used everywhere.
COURSE_CODE_RE = re.compile(r"^([A-Za-z]+)\s+([A-Za-z0-9]+)")

# How many documents may be refused at the edge in a row before the stage gives up. Three is
# enough to tell a rejected client apart from one unlucky document, and small enough that a
# blocked run costs three requests rather than hundreds.
BLOCK_THRESHOLD = 3


class ScrapeError(RuntimeError):
    """The site answered, but not with anything this pipeline recognises."""


class NotRetryable(ScrapeError):
    """The site gave a definite answer. Asking again more slowly will not change it."""


class BlockedError(NotRetryable):
    """The CDN rejected the client outright. Retrying makes it worse, not better."""


class DocumentGone(NotRetryable):
    """The section was in the listing but the site no longer serves it.

    Not an error in the run: sections are withdrawn, and the listing is a snapshot taken
    minutes before the fetch. Counted separately so it does not read as a fault.
    """


def course_code_from_title(title: str) -> Optional[str]:
    match = COURSE_CODE_RE.match((title or "").strip())
    return f"{match.group(1).upper()} {match.group(2).upper()}" if match else None


def html_to_text(html: str) -> str:
    soup = BeautifulSoup(html, "html.parser")
    for tag in soup(["script", "style"]):
        tag.decompose()
    return soup.get_text(separator="\n", strip=True)


def _collect_html_strings(node: Any, out: list[str]) -> None:
    if isinstance(node, dict):
        for value in node.values():
            _collect_html_strings(value, out)
    elif isinstance(node, list):
        for item in node:
            _collect_html_strings(item, out)
    elif isinstance(node, str) and "<" in node and ">" in node:
        text = html_to_text(node)
        if text:
            out.append(text)


def flatten_document(raw: dict[str, Any]) -> str:
    """Every HTML string anywhere in the response, rendered to text and deduplicated.

    Crude, and deliberately so. The response nests the syllabus across a tree of template
    sections whose shape varies by faculty, and walking it properly would mean tracking a
    schema the vendor does not publish. Flattening everything and letting the model sort it
    out has survived a full corpus; a targeted walk would not have.
    """
    chunks: list[str] = []
    _collect_html_strings(raw, chunks)

    seen: set[str] = set()
    cleaned: list[str] = []
    for chunk in chunks:
        normalised = "\n".join(line.strip() for line in chunk.splitlines() if line.strip())
        if normalised and normalised not in seen:
            seen.add(normalised)
            cleaned.append(normalised)
    return "\n\n".join(cleaned)


class SimpleSyllabusClient:
    def __init__(self, config: ScrapeConfig):
        self.config = config
        self.session = requests.Session()
        self.session.headers.update(
            {
                "User-Agent": config.user_agent,
                "Accept": "application/json, text/plain, */*",
            }
        )

    def close(self) -> None:
        self.session.close()

    def _get_json(self, url: str, params: list[tuple[str, str]]) -> dict[str, Any]:
        backoff = 1.0
        last_error: Optional[Exception] = None

        for attempt in range(1, self.config.max_retries + 1):
            try:
                response = self.session.get(
                    url, params=params, timeout=self.config.timeout_sec
                )

                # Back off on the site's terms, not ours. A 429 answered by an immediate
                # retry is how a polite scraper becomes a blocked one.
                if response.status_code == 429:
                    wait = float(response.headers.get("Retry-After", backoff))
                    log.warning("429 from %s, waiting %.1fs", url, wait)
                    time.sleep(wait)
                    backoff = min(backoff * 2, 60.0)
                    continue

                # A 403 from the CDN is a decision about this client, not a transient fault.
                # The same request will be refused every time, so the retry loop only adds
                # four more blocked requests per document -- which is exactly the traffic
                # that turns an edge rule into an IP ban. Fail immediately instead.
                if response.status_code == 403:
                    raise BlockedError(
                        f"403 from {response.headers.get('server', 'the server')} for {url}. "
                        "The edge is rejecting this client rather than the application "
                        "refusing the request. Try SYLLABUS_USER_AGENT."
                    )

                # Same reasoning for the rest of the 4xx range, 429 aside: a 404 or a 400 is
                # an answer, and asking again more slowly does not change it.
                if 400 <= response.status_code < 500:
                    raise NotRetryable(
                        f"{response.status_code} from {url}: "
                        f"{response.reason or 'client error'}"
                    )

                response.raise_for_status()
                return response.json()
            except NotRetryable:
                # Deliberately outside the retry loop. Catching it below would put back
                # exactly the four extra refused requests this exists to prevent.
                raise
            except Exception as exc:  # noqa: BLE001 - retried, then re-raised below
                last_error = exc
                if attempt == self.config.max_retries:
                    break
                log.warning(
                    "%s failed (attempt %d/%d): %s",
                    url,
                    attempt,
                    self.config.max_retries,
                    exc,
                )
                time.sleep(backoff)
                backoff = min(backoff * 2, 60.0)

        raise ScrapeError(f"{url} failed after {self.config.max_retries} attempts") from last_error

    def listings(self) -> list[dict[str, Any]]:
        """Every section for the configured term statuses, deduplicated by doc_code."""
        base_params = [("term_statuses[]", status) for status in self.config.term_statuses]

        first = self._get_json(
            self.config.library_search_url,
            base_params + [("page", "0"), ("page_size", str(self.config.page_size))],
        )

        pagination = first.get("pagination")
        if not isinstance(pagination, dict) or "total" not in pagination:
            raise ScrapeError(
                "doc-library-search response has no usable 'pagination' object. "
                f"Top-level keys were: {sorted(first)}. The endpoint has probably changed."
            )

        total = int(pagination.get("total", 0))
        page_size = int(pagination.get("page_size", self.config.page_size)) or self.config.page_size
        if total <= 0:
            raise ScrapeError(
                f"doc-library-search reported 0 sections for term_statuses="
                f"{list(self.config.term_statuses)}. That is never right mid-term; "
                "check the term status names before assuming the catalog is empty."
            )

        pages = (total + page_size - 1) // page_size
        log.info("listing: %d sections across %d pages", total, pages)

        collected: dict[str, dict[str, Any]] = {}
        self._absorb(first, collected)

        for page in range(1, pages):
            time.sleep(self.config.request_delay_sec)
            payload = self._get_json(
                self.config.library_search_url,
                base_params + [("page", str(page)), ("page_size", str(page_size))],
            )
            self._absorb(payload, collected)

        if not collected:
            raise ScrapeError(
                f"doc-library-search claimed {total} sections but none had both a 'code' "
                "and a 'title'. The item shape has probably changed."
            )
        return list(collected.values())

    @staticmethod
    def _absorb(payload: dict[str, Any], into: dict[str, dict[str, Any]]) -> None:
        items = payload.get("items")
        if not isinstance(items, list):
            raise ScrapeError(
                f"doc-library-search page has no 'items' list. Keys were: {sorted(payload)}"
            )
        for item in items:
            code = item.get("code")
            title = item.get("title", "")
            if not isinstance(code, str) or not isinstance(title, str):
                continue
            into.setdefault(
                code,
                {
                    "doc_code": code,
                    "title_raw": title,
                    "course_code": course_code_from_title(title),
                    "subtitle": item.get("subtitle"),
                    "term_name": item.get("term_name"),
                    "entity_id": item.get("entity_id"),
                    "entity_type": item.get("entity_type"),
                    "family_name": item.get("family_name"),
                },
            )

    def full_document(self, doc_code: str) -> dict[str, Any]:
        payload = self._get_json(self.config.full_page_url, [("code", doc_code)])
        if not isinstance(payload, dict) or not payload:
            raise ScrapeError(f"doc-full-page-get returned nothing usable for {doc_code}")

        # A code the site does not know answers 200 with an empty envelope:
        # {"pagination": {"total": 0}, "items": []}. There is no 404 on this endpoint.
        #
        # It is a real case rather than a hypothetical: the listing and the fetch are minutes
        # apart across a full run, and a section withdrawn in between lands here. Without
        # this it would flatten to empty text and be recorded as a fetch failure, which reads
        # in the run report exactly like a network fault and would have someone chasing it.
        if not payload.get("items"):
            raise DocumentGone(
                f"{doc_code} is no longer published: the endpoint returned an empty envelope"
            )
        return payload


def run(store: Store, config: ScrapeConfig, run_id: str) -> dict[str, Any]:
    client = SimpleSyllabusClient(config)
    try:
        listings = client.listings()
        known = store.known_source_codes()

        pending = [item for item in listings if item["doc_code"] not in known]
        if config.max_new_docs is not None:
            pending = pending[: config.max_new_docs]

        log.info(
            "listing carries %d section(s); %d already stored, %d to fetch",
            len(listings),
            len(listings) - len([i for i in listings if i["doc_code"] not in known]),
            len(pending),
        )

        fetched = 0
        skipped_no_course_code: list[str] = []
        failures: list[dict[str, str]] = []
        consecutive_blocks = 0
        blocked = False
        gone: list[str] = []

        for item in pending:
            doc_code = item["doc_code"]

            # A section whose title does not parse into a course code cannot be keyed in the
            # catalog, so fetching its 120KB of HTML buys nothing. Recorded, not silently
            # dropped: a run of these means the title format moved.
            if not item.get("course_code") or not item.get("term_name"):
                skipped_no_course_code.append(doc_code)
                continue

            try:
                raw = client.full_document(doc_code)
                text = flatten_document(raw)
                if not text.strip():
                    raise ScrapeError("document flattened to empty text")
                store.upsert_source(
                    doc_code=doc_code,
                    listing=item,
                    text=text,
                    raw=raw if config.store_raw else None,
                    run_id=run_id,
                )
                fetched += 1
                consecutive_blocks = 0
            except DocumentGone:
                consecutive_blocks = 0
                gone.append(doc_code)
            except BlockedError as exc:
                consecutive_blocks += 1
                failures.append({"doc_code": doc_code, "error": str(exc)})
                # Every document is refused for the same reason, so continuing means several
                # hundred more rejected requests against an edge that has already said no.
                # That is how a rule that refuses a client becomes one that bans an address.
                if consecutive_blocks >= BLOCK_THRESHOLD:
                    log.error(
                        "aborting: %d consecutive documents refused at the edge. "
                        "Nothing is wrong with the listing, which fetched fine -- it is the "
                        "document endpoint refusing this client. Check SYLLABUS_USER_AGENT.",
                        consecutive_blocks,
                    )
                    blocked = True
                    break
            except Exception as exc:  # noqa: BLE001 - recorded in the run report
                consecutive_blocks = 0
                log.error("fetch failed for %s: %s", doc_code, exc)
                failures.append({"doc_code": doc_code, "error": f"{type(exc).__name__}: {exc}"})

            time.sleep(config.request_delay_sec)

        if skipped_no_course_code:
            log.warning(
                "%d section(s) had no parseable course code or term, for example %s",
                len(skipped_no_course_code),
                skipped_no_course_code[:5],
            )

        return {
            "listed": len(listings),
            "fetched": fetched,
            "already_known": len(known & {i["doc_code"] for i in listings}),
            "skipped_unparseable": len(skipped_no_course_code),
            "skipped_doc_codes": skipped_no_course_code[:50],
            "blocked": blocked,
            "no_longer_published": len(gone),
            "failures": failures,
        }
    finally:
        client.close()


def terms_in(listings: Iterable[dict[str, Any]]) -> set[str]:
    return {item.get("term_name") for item in listings if item.get("term_name")}
