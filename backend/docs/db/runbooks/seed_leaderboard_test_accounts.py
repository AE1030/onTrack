#!/usr/bin/env python3
"""
Seeds local-dev test accounts for the leaderboard and multi-term toggle features.

Accounts are created through the real HTTP flow (POST /register, then GET /verify with the
token pulled out of secure_token) so that Users, the ROLE_USER grant and the Student row are
all built by the application itself.

Everything after that is written straight to Postgres and Mongo, because three kinds of field
cannot be produced through the API in a local dev environment:

  * grades and GPAs are AES-GCM encrypted by JPA converters / Spring Data value converters,
  * SchemeAssessment.firstGradedAt is server-set and must land inside the assessment's own
    14-day window, which for a Winter 2026 term is months in the past,
  * the leaderboard baseline normally comes from a parsed Mosaic transcript PDF.

Scores are NOT written here. The last step calls POST /internal/jobs/leaderboard-recompute so
the application's own LeaderboardRankingService computes every score, rank and behaviour flag.

Run with no arguments, against a running local backend:

    python3 backend/docs/db/runbooks/seed_leaderboard_test_accounts.py

Needs psycopg2, pymongo, requests and cryptography. Idempotent: re-running rebuilds the seeded
rows in place rather than duplicating them, so it is also how you re-seed after a database reset.

LOCAL DEV ONLY. It writes a known password for every account and reaches past the API into both
stores, neither of which has any business happening anywhere but a throwaway database.
"""

import base64
import hashlib
import os
import sys
import time
from datetime import datetime, timedelta, timezone
from decimal import Decimal

import psycopg2
import pymongo
import requests
from bson import Int64
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# ---------------------------------------------------------------- environment

BASE_URL = "http://localhost:8080"
PG = dict(host="172.25.48.1", port=5432, dbname="tracker_db", user="postgres", password="postgres")
MONGO_URI = "mongodb://172.25.48.1:27017/tracker_db"

JOBS_TOKEN = "dev-jobs-token"
# backend/src/main/resources/application-dev.properties, found relative to this runbook so the
# script keeps working from any working directory.
PROPS = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "..", "..", "src", "main", "resources", "application-dev.properties")


def grade_key() -> bytes:
    """
    The AES key the running backend actually encrypts grades with.

    Resolved in Spring Boot's own precedence order, which matters here: OS environment
    variables outrank application-{profile}.properties, so a GRADE_ENCRYPTION_KEY exported in
    the shell that launched the app wins over the key committed in application-dev.properties.
    The two are different on this machine, and encrypting with the wrong one produces rows the
    app answers with a 500 the moment it tries to read them.
    """
    key = os.environ.get("GRADE_ENCRYPTION_KEY")
    source = "environment"
    if not key:
        with open(PROPS) as f:
            for line in f:
                if line.startswith("GRADE_ENCRYPTION_KEY="):
                    key = line.split("=", 1)[1].strip()
                    source = PROPS
                    break
    if not key:
        raise SystemExit("GRADE_ENCRYPTION_KEY is not set in the environment or in " + PROPS)
    print(f"grade encryption key from {source}")
    return base64.b64decode(key)


GRADE_KEY = grade_key()

CURRENT_TERM = "Winter 2026"   # app.current-term
PAST_TERM = "Fall 2025"        # precedes the current term, so it is view only
FUTURE_TERM = "Fall 2026"      # follows it: sorts above in the picker, still not editable

PASSWORD = "TestPass123"       # 8+ chars, one upper, one lower, one digit

# ---------------------------------------------------------------- crypto

def encrypt(plaintext: str) -> str:
    """base64(iv || ciphertext||tag), matching BigDecimal/StringGradeEncryptionConverter."""
    if plaintext is None:
        return None
    iv = os.urandom(12)
    ct = AESGCM(GRADE_KEY).encrypt(iv, plaintext.encode(), None)
    return base64.b64encode(iv + ct).decode()


def decrypt(stored: str) -> str:
    raw = base64.b64decode(stored)
    return AESGCM(GRADE_KEY).decrypt(raw[:12], raw[12:], None).decode()

# ---------------------------------------------------------------- grade tables (GradeDict)

MAC = {"A+": 12.0, "A": 11.0, "A-": 10.0, "B+": 9.0, "B": 8.0, "B-": 7.0,
       "C+": 6.0, "C": 5.0, "C-": 4.0, "D+": 3.0, "D": 2.0, "D-": 1.0, "F": 0.0}
STD = {"A+": 4.0, "A": 3.9, "A-": 3.7, "B+": 3.3, "B": 3.0, "B-": 2.7,
       "C+": 2.3, "C": 2.0, "C-": 1.7, "D+": 1.3, "D": 1.0, "D-": 0.7, "F": 0.0}


def to_letter(pct: float) -> str:
    """NumericGradeConverter.toLetter: HALF_UP to an integer, then banded."""
    r = int(Decimal(str(pct)).quantize(Decimal("1"), rounding="ROUND_HALF_UP"))
    for floor, letter in ((90, "A+"), (85, "A"), (80, "A-"), (77, "B+"), (73, "B"), (70, "B-"),
                          (67, "C+"), (63, "C"), (60, "C-"), (57, "D+"), (53, "D"), (50, "D-")):
        if r >= floor:
            return letter
    return "F"


def gpa(pairs, dict_) -> Decimal:
    """GPACalc.getGPA over (letter, units) pairs: 3 decimals HALF_UP, then 2."""
    earned = Decimal(0)
    attempted = Decimal(0)
    for letter, units in pairs:
        if letter in dict_ and Decimal(str(units)) != 0:
            earned += Decimal(str(dict_[letter])) * Decimal(str(units))
            attempted += Decimal(str(units))
    if attempted == 0:
        return Decimal("0.00")
    q = (earned / attempted).quantize(Decimal("0.001"), rounding="ROUND_HALF_UP")
    return q.quantize(Decimal("0.01"), rounding="ROUND_HALF_UP")

# ---------------------------------------------------------------- seed definitions

# A three-row scheme whose weights sum to 100. Due dates sit inside the Winter 2026 term and
# each grade is stamped a few days after its due date, so CourseScoring's entry window accepts it.
STD_ROWS = [
    ("Assignment 1", 20, "2026-02-06", 4),
    ("Midterm",      30, "2026-02-27", 5),
    ("Final Exam",   50, "2026-04-15", 5),
]

# Same weights, but three of the four rows share one due date, which trips BehaviorScoring's
# clustering flag (3 of 4 dated rows on one date is 75%, above the 60% threshold).
CLUSTERED_ROWS = [
    ("Quiz 1",     25, "2026-03-10", 3),
    ("Quiz 2",     25, "2026-03-10", 4),
    ("Lab Report", 25, "2026-03-10", 5),
    ("Final Exam", 25, "2026-04-15", 5),
]


def scheme(rows, grades):
    """One AssessmentScheme. grades parallel to rows; None leaves the row ungraded."""
    assessments = []
    for (name, weight, due, lag), grade in zip(rows, grades):
        row = {
            "name": name,
            "description": "",
            "location": "",
            "dueDate": due,
            "startTime": "",
            "endTime": "",
            "weight": str(weight),
            "grade": encrypt(str(float(grade))) if grade is not None else None,
            "dueDateChangeCount": 0,
        }
        if grade is not None:
            graded = datetime.fromisoformat(due).replace(tzinfo=timezone.utc) + timedelta(days=lag)
            row["firstGradedAt"] = graded
            row["lastGradedAt"] = graded
        assessments.append(row)
    return {"schemeName": "Marking Scheme 1", "assessments": assessments}


def pct_of(rows, grades):
    """What CourseScoring.schemePercentage works this scheme out to."""
    return sum(w / 100.0 * g for (_, w, _, _), g in zip(rows, grades))


# Transcript history, shared by every account but the CEILING one: five 3-unit courses
# averaging exactly 8.40 on the 12-point scale.
PAST_840 = [
    ("COMPSCI 1MD3", "A-", "3"),
    ("MATH 1ZA3",    "B+", "3"),
    ("MATH 1ZB3",    "B",  "3"),
    ("PHYSICS 1D03", "B",  "3"),
    ("PSYCH 1X03",   "B-", "3"),
]
# Same five courses at 11.40, for the student with no room left to grow.
PAST_1140 = [
    ("COMPSCI 1MD3", "A+", "3"),
    ("MATH 1ZA3",    "A+", "3"),
    ("MATH 1ZB3",    "A+", "3"),
    ("PHYSICS 1D03", "A",  "3"),
    ("PSYCH 1X03",   "A-", "3"),
]

# Fall 2025 enrolments, the read-only term. Percentages line up with the transcript letters.
FALL_840 = [("COMPSCI 1MD3", 82.0), ("MATH 1ZA3", 78.0), ("PHYSICS 1D03", 75.0)]
FALL_1140 = [("COMPSCI 1MD3", 95.0), ("MATH 1ZA3", 93.0), ("PHYSICS 1D03", 91.0)]

ACCOUNTS = [
    dict(key="climber", username="tclimber", handle="SummitSeeker", avatar="1,3,2,1,4",
         note="GROWTH, target reached and saturated. Top of the board.",
         past=PAST_840, fall=FALL_840, target="9.40", status="ACTIVE",
         current=[("COMPSCI 2C03", STD_ROWS, [88, 90, 94]),
                  ("COMPSCI 2DB3", STD_ROWS, [92, 94, 96])]),

    dict(key="tie-a", username="ttiea", handle="AlphaTwin", avatar="2,1,5,0,1",
         note="Tie pair, first half. Identical score to BetaTwin from different percentages.",
         past=PAST_840, fall=FALL_840, target="9.40", status="ACTIVE",
         current=[("COMPSCI 2C03", STD_ROWS, [84, 86, 88]),
                  ("COMPSCI 2DB3", STD_ROWS, [86, 88, 87])]),

    dict(key="tie-b", username="ttieb", handle="BetaTwin", avatar="3,5,1,2,0",
         note="Tie pair, second half. Ranks below AlphaTwin on the handle tiebreak.",
         past=PAST_840, fall=FALL_840, target="9.40", status="ACTIVE",
         current=[("COMPSCI 2C03", STD_ROWS, [90, 85, 86]),
                  ("COMPSCI 2DB3", STD_ROWS, [88, 84, 89])]),

    dict(key="ceiling", username="tceiling", handle="TopOfScale", avatar="0,6,0,3,2",
         note="CEILING mode: baseline above 11.0, so there is no growth gap to declare.",
         past=PAST_1140, fall=FALL_1140, target="12.00", status="ACTIVE",
         current=[("COMPSCI 2C03", STD_ROWS, [92, 94, 96]),
                  ("COMPSCI 2DB3", STD_ROWS, [90, 92, 94])]),

    dict(key="clustered", username="tclustered", handle="BusyWeek", avatar="4,2,6,1,5",
         note="Same raw performance as the tie pair, damped by one clustering flag.",
         past=PAST_840, fall=FALL_840, target="9.40", status="ACTIVE",
         current=[("COMPSCI 2C03", CLUSTERED_ROWS, [87, 87, 87, 87]),
                  ("COMPSCI 2DB3", STD_ROWS, [86, 87, 87])]),

    dict(key="maintain", username="tmaintain", handle="SteadyHand", avatar="5,4,3,0,3",
         note="MAINTENANCE mode: target under a point above baseline, so a 7,000 ceiling.",
         past=PAST_840, fall=FALL_840, target="9.00", status="ACTIVE",
         current=[("COMPSCI 2C03", STD_ROWS, [77, 78, 79]),
                  ("COMPSCI 2DB3", STD_ROWS, [79, 77, 78])]),

    dict(key="solo", username="tsolo", handle="QuietStart", avatar="1,0,4,2,1",
         note="Joined, enrolled, nothing graded yet. Sits on the recovery floor at 1,000.",
         past=PAST_840, fall=FALL_840, target="10.40", status="ACTIVE",
         current=[("COMPSCI 2C03", STD_ROWS, [None, None, None]),
                  ("COMPSCI 2DB3", STD_ROWS, [None, None, None])]),

    dict(key="slipping", username="tslipping", handle="RecoveryArc", avatar="2,7,7,3,0",
         note="Fell below baseline. Scores inside the recovery band only.",
         past=PAST_840, fall=FALL_840, target="9.40", status="ACTIVE",
         current=[("COMPSCI 2C03", STD_ROWS, [55, 52, 54]),
                  ("COMPSCI 2DB3", STD_ROWS, [62, 60, 64])]),

    dict(key="withdrawn", username="twithdrawn", handle="SteppedAway", avatar="0,2,1,1,2",
         note="WITHDRAWN with a top-tier score. Must not appear on the board at all.",
         past=PAST_840, fall=FALL_840, target="9.40", status="WITHDRAWN",
         current=[("COMPSCI 2C03", STD_ROWS, [88, 90, 94]),
                  ("COMPSCI 2DB3", STD_ROWS, [92, 94, 96])]),
]

# One account also carries a Fall 2026 term, which follows Winter 2026 in this codebase's
# season order and so sorts above it in the picker while still being read only.
FUTURE_TERM_FOR = "climber"
FUTURE_COURSES = [("COMPSCI 2GA3", None), ("STATS 2D03", None)]

CURRENT_COURSE_PCT = {}  # filled in per account below

# ---------------------------------------------------------------- database helpers


def course_ids(cur, codes):
    cur.execute("select course_code, id, course_credits from course where course_code = any(%s)",
                (list(codes),))
    return {code: (cid, credits) for code, cid, credits in cur.fetchall()}


def auth_call(method, path, **kwargs):
    """
    One call to an /auth endpoint, waiting out RateLimitFilter.

    The filter allows 10 requests per minute per IP across /login, /register and /verify
    together, and seeding needs two per account, so a run of this size will trip it.
    """
    for attempt in range(12):
        r = requests.request(method, f"{BASE_URL}{path}", timeout=30, **kwargs)
        if r.status_code != 429:
            return r
        print(f"    rate limited on {path}, waiting for the window to reset", flush=True)
        time.sleep(20)
    raise SystemExit(f"{path} still rate limited after several attempts")


def register(email, username):
    """Register, verify, and return the student id. Skips work already done."""
    # Asked of the database first, not of /register: the endpoint answers an existing email
    # with a 500 (UserService throws IllegalArgumentException, which nothing maps), so a
    # re-run could not tell "already seeded" apart from a real failure.
    with psycopg2.connect(**PG) as conn, conn.cursor() as cur:
        cur.execute("select id from users where email=%s", (email,))
        existing = cur.fetchone()

    created = existing is None
    if created:
        r = auth_call("POST", "/register",
                      json={"email": email, "username": username, "password": PASSWORD})
        if r.status_code != 201:
            raise SystemExit(f"register {email} failed: {r.status_code} {r.text[:200]}")

    with psycopg2.connect(**PG) as conn, conn.cursor() as cur:
        cur.execute("select id, email_verified from users where email=%s", (email,))
        row = cur.fetchone()
        if row is None:
            raise SystemExit(f"{email} was not registered and does not exist: {r.text[:200]}")
        user_id, verified = row
        if not verified:
            cur.execute("""select token from secure_token
                           where user_id=%s order by id desc limit 1""", (user_id,))
            tok = cur.fetchone()
            if tok is None:
                raise SystemExit(f"no verification token for {email}")
            token = tok[0]
        else:
            token = None

    if token:
        v = auth_call("GET", "/verify", params={"token": token})
        if v.status_code != 200:
            raise SystemExit(f"verify {email} failed: {v.status_code} {v.text[:200]}")

    with psycopg2.connect(**PG) as conn, conn.cursor() as cur:
        cur.execute("select id from student where user_id=%s", (user_id,))
        row = cur.fetchone()
        if row is None:
            raise SystemExit(f"no student row for {email} after verification")
    return user_id, row[0], created


def verify_key(cur):
    """
    Proves the key round-trips against a row the application itself wrote.

    Without this the first symptom of a wrong key is a 500 from /login, because the Student
    entity decrypts its GPA columns on load. Any pre-existing encrypted value will do.
    """
    seeded = [a["username"] for a in ACCOUNTS]
    cur.execute("""select s.id, s.gpa12 from student s
                   join users u on u.id = s.user_id
                   where s.gpa12 is not null and u.username <> all(%s)""", (seeded,))
    candidates = cur.fetchall()
    if not candidates:
        print("no pre-existing encrypted row to check the key against; continuing")
        return
    for student_id, value in candidates:
        try:
            print(f"key check: student {student_id}'s stored GPA decrypts to {decrypt(value)}")
            return
        except Exception:
            # Not every legacy row is AES-GCM: one predates the converter and holds "enc:9".
            continue
    raise SystemExit(
        "the resolved GRADE_ENCRYPTION_KEY decrypted none of "
        f"{len(candidates)} existing student GPA rows.\n"
        "Seeding with it would produce rows that 500 on read. Check which key the running "
        "backend was started with: tr '\\0' '\\n' < /proc/<pid>/environ | grep GRADE")


def main():
    now = datetime.now(timezone.utc)
    all_codes = {c for a in ACCOUNTS for c, _, _ in a["past"]}
    all_codes |= {c for a in ACCOUNTS for c, _ in a["fall"]}
    all_codes |= {c for a in ACCOUNTS for c, _, _ in a["current"]}
    all_codes |= {c for c, _ in FUTURE_COURSES}

    conn = psycopg2.connect(**PG)
    conn.autocommit = False
    cur = conn.cursor()
    verify_key(cur)
    catalog = course_ids(cur, all_codes)
    missing = all_codes - set(catalog)
    if missing:
        raise SystemExit(f"courses missing from the catalog: {sorted(missing)}")

    mongo = pymongo.MongoClient(MONGO_URI)
    tables = mongo.get_database("tracker_db").get_collection("assessmentTable")

    report = []

    for acct in ACCOUNTS:
        email = f"{acct['key'].replace('-', '.')}@mcmaster.ca"
        user_id, student_id, created = register(email, acct["username"])

        # ----- wipe anything a previous run seeded for this student
        cur.execute("delete from leaderboard_rank_history where student_id=%s", (student_id,))
        cur.execute("delete from leaderboard_entry where student_id=%s", (student_id,))
        cur.execute("delete from leaderboard_profile where student_id=%s", (student_id,))
        cur.execute("delete from season_baseline where student_id=%s", (student_id,))
        cur.execute("delete from transcript_upload where student_id=%s", (student_id,))
        cur.execute("delete from past_course where student_id=%s", (student_id,))
        cur.execute("delete from course_enrollement where student_id=%s", (student_id,))
        tables.delete_many({"studentId": Int64(student_id)})

        # ----- transcript history
        for code, letter, units in acct["past"]:
            cur.execute("""insert into past_course
                             (student_id, name, grade, units, created_at, updated_at)
                           values (%s,%s,%s,%s,%s,%s)""",
                        (student_id, code, encrypt(letter), units, now, now))

        baseline = gpa([(l, u) for _, l, u in acct["past"]], MAC)

        # ----- Fall 2025: the read-only term
        for code, pct in acct["fall"]:
            cid, _ = catalog[code]
            cur.execute("""insert into course_enrollement
                             (student_id, course_id, term, grade, include_in_gpa,
                              created_at, updated_at)
                           values (%s,%s,%s,%s,true,%s,%s)""",
                        (student_id, cid, PAST_TERM, encrypt(str(pct)), now, now))

        # ----- Winter 2026: the current, editable term
        projected_pairs = [(l, u) for _, l, u in acct["past"]]
        for code, rows, grades in acct["current"]:
            cid, credits = catalog[code]
            graded = [g for g in grades if g is not None]
            pct = pct_of(rows, grades) if graded else None
            cur.execute("""insert into course_enrollement
                             (student_id, course_id, term, grade, include_in_gpa,
                              created_at, updated_at)
                           values (%s,%s,%s,%s,true,%s,%s)""",
                        (student_id, cid, CURRENT_TERM,
                         encrypt(str(round(pct, 2))) if pct is not None else None, now, now))
            tables.insert_one({
                "courseCode": code,
                "term": CURRENT_TERM,
                "schemes": [scheme(rows, grades)],
                "studentId": Int64(student_id),
                "createdAt": now,
                "updatedAt": now,
                "_class": "org.tracker.gpatracker.assessmenttable.model.AssessmentTableDocument",
            })
            if pct is not None:
                projected_pairs.append((to_letter(pct), str(credits)))

        # ----- Fall 2026, only on the one account, as a picker-ordering case
        if acct["key"] == FUTURE_TERM_FOR:
            for code, _ in FUTURE_COURSES:
                cid, _ = catalog[code]
                cur.execute("""insert into course_enrollement
                                 (student_id, course_id, term, grade, include_in_gpa,
                                  created_at, updated_at)
                               values (%s,%s,%s,null,true,%s,%s)""",
                            (student_id, cid, FUTURE_TERM, now, now))

        projected12 = gpa(projected_pairs, MAC)
        projected4 = gpa(projected_pairs, STD)

        # ----- dashboard GPAs
        cur.execute("""update student set gpa12=%s, gpa4=%s,
                              target_gpa12=%s, target_gpa4=%s, updated_at=%s
                        where id=%s""",
                    (encrypt(str(projected12)), encrypt(str(projected4)),
                     encrypt(acct["target"]), encrypt(str(four_point(Decimal(acct["target"])))),
                     now, student_id))

        # ----- the transcript upload the baseline came from
        digest = hashlib.sha256(f"seed::{acct['key']}::{CURRENT_TERM}".encode()).hexdigest()
        cur.execute("""insert into transcript_upload
                         (student_id, season, file_sha256, parsed_gpa12, uploaded_at,
                          created_at, updated_at)
                       values (%s,%s,%s,%s,%s,%s,%s)""",
                    (student_id, CURRENT_TERM, digest, encrypt(str(baseline)), now, now, now))

        # ----- the frozen season, and the entry the recompute will score
        mode = mode_for(float(baseline), float(Decimal(acct["target"])))
        cur.execute("""insert into season_baseline
                         (student_id, season, baseline_gpa12, target_gpa12, score_mode,
                          created_at, updated_at)
                       values (%s,%s,%s,%s,%s,%s,%s)""",
                    (student_id, CURRENT_TERM, encrypt(str(baseline)),
                     encrypt(acct["target"]), mode, now, now))
        cur.execute("""insert into leaderboard_profile
                         (student_id, handle, avatar, opted_in_at, rules_accepted_at,
                          created_at, updated_at)
                       values (%s,%s,%s,%s,%s,%s,%s)""",
                    (student_id, acct["handle"], acct["avatar"], now, now, now, now))
        cur.execute("""insert into leaderboard_entry
                         (student_id, season, handle, avatar, score, status, score_mode,
                          ambition, behavior_score, behavior_flags, created_at, updated_at)
                       values (%s,%s,%s,%s,0,%s,%s,0,1,0,%s,%s)""",
                    (student_id, CURRENT_TERM, acct["handle"], acct["avatar"],
                     acct["status"], mode, now, now))

        report.append(dict(key=acct["key"], email=email, username=acct["username"],
                           student_id=student_id, handle=acct["handle"], mode=mode,
                           baseline=baseline, target=acct["target"], projected=projected12,
                           status=acct["status"], note=acct["note"], created=created))

    # Every seeded entry is inserted with a null rank, and LeaderboardRankingService.assignRanks
    # declines to rank a season that was ranked in the last ten minutes. On a re-run inside that
    # window the guard would fire and the board would be left scored but unranked, so the
    # season's rank history goes too: this run is rebuilding that season from scratch, which is
    # exactly the case the guard is not defending against. The cost is the sparkline samples of
    # any non-seeded student in the same season, which the next scheduled run rebuilds.
    cur.execute("delete from leaderboard_rank_history where season=%s", (CURRENT_TERM,))
    print(f"cleared {cur.rowcount} rank history rows for {CURRENT_TERM} "
          "so the ranking cooldown does not skip this run")

    conn.commit()
    cur.close()
    conn.close()

    # ----- let the application score and rank the board
    r = requests.post(f"{BASE_URL}/internal/jobs/leaderboard-recompute",
                      headers={"X-Jobs-Token": JOBS_TOKEN}, timeout=120)
    print(f"recompute -> {r.status_code} {r.text}\n")
    if r.status_code != 200:
        raise SystemExit("the recompute did not succeed, so scores and ranks are not seeded")

    print(f"{'account':<11} {'email':<26} {'handle':<13} {'mode':<12} "
          f"{'base':>5} {'targ':>5} {'proj':>5}  status")
    for row in report:
        print(f"{row['key']:<11} {row['email']:<26} {row['handle']:<13} {row['mode']:<12} "
              f"{row['baseline']:>5} {row['target']:>5} {row['projected']:>5}  {row['status']}")
    print(f"\npassword for every account: {PASSWORD}")


def four_point(gpa12: Decimal) -> Decimal:
    """GpaScale.toFourPoint: linear interpolation between neighbouring letters."""
    pts = [0.0, 0.7, 1.0, 1.3, 1.7, 2.0, 2.3, 2.7, 3.0, 3.3, 3.7, 3.9, 4.0]
    v = min(max(float(gpa12), 0.0), 12.0)
    lower = int(v)
    if lower >= 12:
        return Decimal("4.00")
    interpolated = pts[lower] + (v - lower) * (pts[lower + 1] - pts[lower])
    return Decimal(str(interpolated)).quantize(Decimal("0.01"), rounding="ROUND_HALF_UP")


def mode_for(baseline: float, target: float) -> str:
    """LeaderboardScoring.modeFor."""
    if (12.0 - baseline) < 1.0:
        return "CEILING"
    return "GROWTH" if target - baseline >= 1.0 else "MAINTENANCE"


if __name__ == "__main__":
    sys.exit(main())
