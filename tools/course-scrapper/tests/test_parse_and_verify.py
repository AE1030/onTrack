"""Parser and verify() over hand-written page text shaped like pypdf's output of the calendar.

No network, no PDF and no database: every page here is a string, the way pdf_pages() returns
them, so these run anywhere in a second.
"""

import pytest

LISTING_HEADER = "|     coUrse listinGs     |     history     |683"
OTHER_HEADER = "|     faculty of science     |     ProGrams     | 120"


def page(header, *lines):
    return "\n".join([header, *lines])


def title_pages(year="2026-2027"):
    return [f"McMASTER UNIVERSITY\nUNDERGRADUATE CALENDAR\n{year}\nAcademic Advising Edition"]


def codes(result):
    return {c.course_code: c for c in result.courses}


# -------------------- parsing --------------------


def test_plain_heading(cs):
    pages = title_pages() + [
        page(
            LISTING_HEADER,
            "HISTORY 4HH3 - CHINA’S GREAT CULTURAL REVOLUTION",
            "A critical assessment of the origins of the campaign.",
            "Seminar (two hours); one term",
        )
    ]
    c = codes(cs.parse_courses(pages))["HISTORY 4HH3"]
    assert c.course_name == "CHINA’S GREAT CULTURAL REVOLUTION"
    assert c.course_credits == 3


def test_title_wrapped_over_lines_and_pages(cs):
    pages = title_pages() + [
        page(LISTING_HEADER, "GERMAN 2P03 - MODERN GERMANY THROUGH FILM: "),
        page(
            LISTING_HEADER,
            "GOLDEN 20S - AVANT GARDE - TRANSNATIONAL CINEMA ",
            "(TAUGHT IN ENGLISH)",
            "Films from the Weimar Republic to the present.",
        ),
    ]
    result = cs.parse_courses(pages)
    assert codes(result)["GERMAN 2P03"].course_name == (
        "MODERN GERMANY THROUGH FILM: GOLDEN 20S - AVANT GARDE - TRANSNATIONAL CINEMA "
        "(TAUGHT IN ENGLISH)"
    )
    # A continuation line that looks a bit like a heading is not reported as unparsed.
    assert result.unparsed_headings == []


@pytest.mark.parametrize(
    "line, code, credits",
    [
        ("MUSIC 1GW3 A/B - ENSEMBLE PERFORMANCE", "MUSIC 1GW3 A/B", 3),
        ("ASTRON 5X03 A/B S - GRADUATE TOPICS IN ASTROPHYSICS", "ASTRON 5X03 A/B S", 3),
        ("COMPSCI 4ZP6 A/B - CAPSTONE PROJECT", "COMPSCI 4ZP6 A/B", 6),
        ("MATH 3CY3* - CRYPTOGRAPHY", "MATH 3CY3", 3),
        ("COMMERCE 2IN0 - CAREER DEVELOPMENT", "COMMERCE 2IN0", 0),
    ],
)
def test_code_forms_match_what_the_backend_stores(cs, line, code, credits):
    pages = title_pages() + [page(LISTING_HEADER, line, "Description text.")]
    c = codes(cs.parse_courses(pages))[code]
    assert c.course_credits == credits


def test_only_course_listing_pages_are_read(cs):
    # Program requirements mention courses in heading-like form; they must not become courses.
    pages = title_pages() + [
        page(OTHER_HEADER, "ECON 1B03 - Introductory Microeconomics with a minimum grade of 5.0"),
        page(LISTING_HEADER, "ECON 1B03 - INTRODUCTORY MICROECONOMICS", "Description."),
    ]
    assert codes(cs.parse_courses(pages))["ECON 1B03"].course_name == "INTRODUCTORY MICROECONOMICS"


def test_description_is_not_swallowed_into_title(cs):
    pages = title_pages() + [
        page(LISTING_HEADER, "CHEM 1A03 - INTRODUCTORY CHEMISTRY I", "Topics include atoms.")
    ]
    assert codes(cs.parse_courses(pages))["CHEM 1A03"].course_name == "INTRODUCTORY CHEMISTRY I"


def test_duplicate_code_keeps_first_and_records_conflict(cs):
    pages = title_pages() + [
        page(
            LISTING_HEADER,
            "HTHSCI 4LB3 - LEADERSHIP AND MANAGEMENT 1",
            "Description.",
            "HTHSCI 4LB3 - LEADERSHIP AND MANAGEMENT 2",
            "Description.",
        )
    ]
    result = cs.parse_courses(pages)
    assert codes(result)["HTHSCI 4LB3"].course_name == "LEADERSHIP AND MANAGEMENT 1"
    assert result.conflicts == [
        ("HTHSCI 4LB3", "LEADERSHIP AND MANAGEMENT 1", "LEADERSHIP AND MANAGEMENT 2")
    ]


def test_unparseable_heading_is_reported(cs):
    pages = title_pages() + [page(LISTING_HEADER, "BIOLOGY 1A0X3 - BROKEN CODE", "Description.")]
    assert cs.parse_courses(pages).unparsed_headings == ["BIOLOGY 1A0X3 - BROKEN CODE"]


def test_requirement_lists_are_not_mistaken_for_headings(cs):
    pages = title_pages() + [
        page(LISTING_HEADER, "MATLS 3LP3, MECHENG 3R03, List 2 (Fluid Mechanics) - CIVENG 2O04,")
    ]
    assert cs.parse_courses(pages).unparsed_headings == []


# -------------------- verify --------------------


@pytest.fixture
def small_limits(cs, monkeypatch):
    """Shrink the size thresholds so a handful of synthetic courses can pass."""
    monkeypatch.setattr(cs, "MIN_COURSES", 3)
    monkeypatch.setattr(cs, "MIN_SUBJECTS", 2)
    monkeypatch.setattr(cs, "SPOT_CHECK_CODES", ["ECON 1B03"])
    monkeypatch.setattr(cs, "MAX_SOFT_ANOMALY_RATE", 0.5)
    return cs


def good_result(cs, **overrides):
    courses = [
        cs.Course("ECON 1B03", "INTRODUCTORY MICROECONOMICS", 3),
        cs.Course("HISTORY 4HH3", "CHINA’S GREAT CULTURAL REVOLUTION", 3),
        cs.Course("COMPSCI 4ZP6 A/B", "CAPSTONE PROJECT", 6),
        cs.Course("CHEM 1A03", "INTRODUCTORY CHEMISTRY I", 3),
    ]
    fields = dict(courses=courses, catalog_text="UNDERGRADUATE CALENDAR\n2026-2027")
    fields.update(overrides)
    return cs.ParseResult(**fields)


def test_verify_passes_a_good_parse(small_limits):
    cs = small_limits
    assert cs.verify(good_result(cs)) == []


def test_verify_rejects_the_wrong_year(small_limits):
    cs = small_limits
    failures = cs.verify(good_result(cs, catalog_text="UNDERGRADUATE CALENDAR\n2025-2026"))
    assert any("2026-2027" in f for f in failures)


def test_verify_rejects_too_few_courses(small_limits, monkeypatch):
    cs = small_limits
    monkeypatch.setattr(cs, "MIN_COURSES", 100)
    assert any("only 4 courses" in f for f in cs.verify(good_result(cs)))


def test_verify_rejects_a_missing_spot_check(small_limits, monkeypatch):
    cs = small_limits
    monkeypatch.setattr(cs, "SPOT_CHECK_CODES", ["MATH 1ZA3"])
    assert any("MATH 1ZA3" in f for f in cs.verify(good_result(cs)))


def test_verify_rejects_a_title_with_description_text(small_limits):
    cs = small_limits
    result = good_result(cs)
    result.courses.append(cs.Course("BIOLOGY 1A03", "CELLULAR BIOLOGY Topics include", 3))
    assert any("lowercase titles" in f for f in cs.verify(result))


def test_verify_rejects_malformed_codes(small_limits):
    cs = small_limits
    result = good_result(cs)
    result.courses.append(cs.Course("BIOLOGY 1A0", "CELLULAR BIOLOGY", 0))
    assert any("malformed codes" in f for f in cs.verify(result))


def test_verify_rejects_unparsed_headings(small_limits):
    cs = small_limits
    result = good_result(cs, unparsed_headings=["BIOLOGY 1A0X3 - BROKEN CODE"])
    assert any("did not parse" in f for f in cs.verify(result))


def test_verify_tolerates_a_few_soft_anomalies_but_not_many(small_limits, monkeypatch):
    cs = small_limits
    conflict = [("ECON 1B03", "INTRODUCTORY MICROECONOMICS", "SOMETHING ELSE")]
    assert cs.verify(good_result(cs, conflicts=conflict)) == []
    monkeypatch.setattr(cs, "MAX_SOFT_ANOMALY_RATE", 0.1)
    assert any("conflicting duplicates" in f for f in cs.verify(good_result(cs, conflicts=conflict)))


def test_verify_rejects_unit_weights_that_look_wrong(small_limits):
    cs = small_limits
    result = good_result(cs)
    result.courses[:] = [cs.Course(c.course_code, c.course_name, 6) for c in result.courses]
    assert any("3 units" in f for f in cs.verify(result))


# -------------------- fetching --------------------


def test_a_non_pdf_download_is_refused(cs, monkeypatch, tmp_path):
    fake = tmp_path / "moved.pdf"
    fake.write_bytes(b"<html>Page not found</html>")
    monkeypatch.setattr(cs, "PDF_PATH", str(fake))
    with pytest.raises(cs.ScrapeError, match="not a PDF"):
        cs.fetch_pdf()


@pytest.mark.parametrize(
    "status, headers, blocked",
    [
        (202, {}, True),
        (403, {}, True),
        (200, {"x-amzn-waf-action": "challenge"}, True),
        (200, {}, False),
    ],
)
def test_challenge_detection(cs, status, headers, blocked):
    assert cs._is_challenge(status, headers) is blocked
