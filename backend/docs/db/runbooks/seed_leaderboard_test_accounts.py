#!/usr/bin/env python3
"""
Seeds local-dev test accounts for the leaderboard, the multi-term picker and the calendar.

Accounts are created through the real HTTP flow (POST /register, then POST /verify with the
token pulled out of secure_token) so that Users, the ROLE_USER grant and the Student row are
all built by the application itself.

Everything after that is written straight to Postgres, because three kinds of field cannot be
produced through the API in a local dev environment:

  * grades and GPAs are AES-GCM encrypted by JPA converters (including the grades nested inside
    assessment_table.schemes, which AssessmentSchemesJsonConverter stores as base64 strings),
  * SchemeAssessment.firstGradedAt is server-set and must land inside the assessment's own
    14-day window, which for the current term may be months in the past,
  * the leaderboard baseline normally comes from a parsed Mosaic transcript PDF.

Scores are NOT written here. The last step calls POST /internal/jobs/leaderboard-recompute so
the application's own LeaderboardRankingService computes every score, rank and behaviour flag.
Ranks are seeded only as *history* (six older samples per student, so the sparkline and the
movement chip have something to show); the recompute decides the live rank.

Run with no arguments, against a running local backend:

    python3 backend/docs/db/runbooks/seed_leaderboard_test_accounts.py

Needs psycopg2, requests and cryptography. Idempotent: re-running rebuilds the seeded rows in
place rather than duplicating them, so it is also how you re-seed after a database reset.

LOCAL DEV ONLY. It writes a known password for every account and reaches past the API into the
database, neither of which has any business happening anywhere but a throwaway database.
"""

import base64
import hashlib
import json
import os
import sys
import time
import uuid
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal

import psycopg2
import requests
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# ---------------------------------------------------------------- environment

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
DOTENV = os.path.join(BACKEND, ".env")
PROPS = os.path.join(BACKEND, "src", "main", "resources", "application-dev.properties")

BASE_URL = "http://localhost:8080"
PASSWORD = "TestPass123"       # 8+ chars, one upper, one lower, one digit


def dotenv() -> dict:
    values = {}
    if os.path.exists(DOTENV):
        with open(DOTENV) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    values[k.strip()] = v.strip()
    return values


ENV = dotenv()


def setting(name, default=None):
    """Spring's precedence for the values this script needs: OS env > backend/.env > default."""
    return os.environ.get(name) or ENV.get(name) or default


def grade_key() -> bytes:
    """
    The AES key the running backend actually encrypts grades with.

    Resolved in the order the app resolves it: OS env, then backend/.env (spring-dotenv), then
    application-dev.properties. The .env key and the properties key differ on this machine, and
    encrypting with the wrong one produces rows the app answers with a 500 the moment it reads them.
    """
    key = os.environ.get("GRADE_ENCRYPTION_KEY")
    source = "OS environment"
    if not key and ENV.get("GRADE_ENCRYPTION_KEY"):
        key, source = ENV["GRADE_ENCRYPTION_KEY"], DOTENV
    if not key and os.path.exists(PROPS):
        with open(PROPS) as f:
            for line in f:
                if line.startswith("GRADE_ENCRYPTION_KEY="):
                    key, source = line.split("=", 1)[1].strip(), PROPS
                    break
    if not key:
        raise SystemExit("GRADE_ENCRYPTION_KEY is not set in the environment, .env or " + PROPS)
    print(f"grade encryption key from {source}")
    return base64.b64decode(key)


def pg_params() -> dict:
    url = setting("DB_URL", "jdbc:postgresql://172.25.48.1:5432/tracker_db")
    hostport, dbname = url.split("//", 1)[1].split("/", 1)
    host, _, port = hostport.partition(":")
    return dict(host=host, port=int(port or 5432), dbname=dbname.split("?")[0],
                user=setting("DB_USERNAME", "postgres"), password=setting("DB_PASSWORD", "postgres"))


GRADE_KEY = grade_key()
PG = pg_params()
JOBS_TOKEN = setting("JOBS_TRIGGER_TOKEN", "dev-jobs-token")
CURRENT_TERM = f"{setting('APP_CURRENT_TERM_SEASON', 'Winter')} {setting('APP_CURRENT_TERM_YEAR', '2026')}"

# ---------------------------------------------------------------- crypto


def encrypt(plaintext):
    """base64(iv || ciphertext||tag), matching BigDecimal/StringGradeEncryptionConverter."""
    if plaintext is None:
        return None
    iv = os.urandom(12)
    ct = AESGCM(GRADE_KEY).encrypt(iv, str(plaintext).encode(), None)
    return base64.b64encode(iv + ct).decode()


def decrypt(stored: str) -> str:
    raw = base64.b64decode(stored)
    return AESGCM(GRADE_KEY).decrypt(raw[:12], raw[12:], None).decode()

# ---------------------------------------------------------------- grade arithmetic (GradeDict)

MAC = {"A+": 12.0, "A": 11.0, "A-": 10.0, "B+": 9.0, "B": 8.0, "B-": 7.0,
       "C+": 6.0, "C": 5.0, "C-": 4.0, "D+": 3.0, "D": 2.0, "D-": 1.0, "F": 0.0}
FOUR = {"A+": 4.0, "A": 3.9, "A-": 3.7, "B+": 3.3, "B": 3.0, "B-": 2.7,
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
    """LeaderboardScoring.modeFor, including its effectiveBaseline (0 is measured from 6.0)."""
    if baseline <= 0.0:
        baseline = 6.0
    if (12.0 - baseline) < 1.0:
        return "CEILING"
    return "GROWTH" if target - baseline >= 1.0 else "MAINTENANCE"

# ---------------------------------------------------------------- terms and dates

SEASON_START = {"winter": (1, 6), "spring/summer": (5, 4), "fall": (9, 3)}
SEASON_ORDER = {"winter": 1, "spring/summer": 2, "fall": 3}


def term_key(term):
    season, year = term.rsplit(" ", 1)
    return int(year), SEASON_ORDER[season.lower()]


def term_start(term) -> date:
    season, year = term.rsplit(" ", 1)
    month, day = SEASON_START[season.lower()]
    return date(int(year), month, day)


def on(term, offset_days) -> str:
    return (term_start(term) + timedelta(days=offset_days)).isoformat()

# ---------------------------------------------------------------- assessment schemes
#
# A row is (name, weight, day offset from term start, days after due when the grade was entered,
# extras). Weights are percentages unless a template says otherwise. Offsets keep the same shapes
# working for any term, so the script survives app.current-term rolling over.

STD = [
    ("Assignment 1", 20, 30, 4, {}),
    ("Midterm", 30, 52, 5, {"startTime": "19:00", "endTime": "21:00", "location": "MDCL 1305"}),
    ("Final Exam", 50, 100, 5, {"startTime": "09:00", "endTime": "11:30", "location": "IWC Gym"}),
]

LABS = [
    ("Lab 1", 10, 14, 3, {}),
    ("Lab 2", 10, 35, 3, {}),
    ("Lab 3", 10, 63, 3, {}),
    ("Midterm Test", 25, 49, 6, {"startTime": "18:30", "endTime": "20:00"}),
    ("Final Exam", 45, 101, 4, {"startTime": "12:30", "endTime": "15:00", "location": "TBA"}),
]

# What the mobile client actually saves: weights as fractions summing to 1.0. CourseScoring accepts
# either convention, so this course scores the same as a percentage one.
FRACTIONS = [
    ("Problem Set 1", 0.15, 21, 2, {}),
    ("Problem Set 2", 0.15, 42, 2, {}),
    ("Project", 0.30, 77, 8, {}),
    ("Final Exam", 0.40, 99, 5, {}),
]

# Three of four rows on one date: BehaviorScoring's clustering flag (75% > 60%).
CLUSTERED = [
    ("Quiz 1", 25, 66, 3, {}),
    ("Quiz 2", 25, 66, 4, {}),
    ("Lab Report", 25, 66, 5, {}),
    ("Final Exam", 25, 100, 5, {}),
]

# Names chosen to stress the calendar cell, the course table and the dashboard list.
LONG_NAMES = [
    ("Assignment 1: Implementing a Self-Balancing Red-Black Tree With Deletion, Iterators and "
     "Full Property-Based Test Coverage", 10, 24, 3, {}),
    ("Midterm Examination 2: Graph Algorithms, Dynamic Programming, Amortized Analysis and "
     "Everything Covered From Week 1 Through Week 7 Inclusive (Closed Book, Calculators Permitted)",
     25, 55, 5, {"startTime": "19:00", "endTime": "21:30",
                 "location": "Burke Science Building B148, with overflow seating in BSB 147 and "
                             "the Ron Joyce Stadium Atrium for students with surnames A to K"}),
    ("Assignment_3_Final_Submission_Including_Peer_Review_Reflection_And_Code_Walkthrough_Video",
     15, 66, 4, {}),
    ("Weekly Reading Quiz", 10, 17, 2, {"recurring": 7}),
    ("Optional Bonus: Presentation of a Research Paper of Your Choice to the Tutorial Section",
     0, 80, 1, {}),
    ("Final Examination (Cumulative, Three Hours, Registrar-Scheduled)", 40, 102, 5,
     {"startTime": "09:00", "endTime": "12:00", "location": "IWC Gym"}),
]

# Same course, two alternative schemes: CourseScoring takes the better of them.
ALT_A = [("Assignments", 30, 40, 4, {}), ("Midterm", 30, 52, 4, {}), ("Final Exam", 40, 100, 4, {})]
ALT_B = [("Assignments", 30, 40, 4, {}), ("Midterm", 0, 52, 4, {}), ("Final Exam", 70, 100, 4, {})]

# Weights sum to 90, so CourseScoring rejects the whole course as malformed.
SHORT_WEIGHTS = [("Essay", 30, 45, 3, {}), ("Midterm", 20, 52, 3, {}), ("Final Exam", 40, 100, 3, {})]


def course(code, rows, grades, **kw):
    """One enrolment with one scheme. grades parallel to rows; None leaves a row ungraded."""
    return dict(code=code, schemes=[("Marking Scheme 1", rows, grades)], **kw)


def multi(code, *schemes, **kw):
    return dict(code=code, schemes=list(schemes), **kw)


def simple(code, pct, **kw):
    """A past-term enrolment: a finished course whose only grade that matters is its final mark."""
    return course(code, STD, [pct, pct, pct], **kw)

# ---------------------------------------------------------------- transcript histories
#
# Past terms. Every one of these becomes both a read-only term in the picker (enrolments plus an
# assessment table) and a past_course row on the transcript, so the baseline the board freezes is
# exactly the GPA of what the picker shows.

H_STANDARD = {
    "Fall 2024": [simple("COMPSCI 1MD3", 82), simple("MATH 1ZA3", 78), simple("PSYCH 1X03", 71)],
    "Fall 2025": [simple("MATH 1ZB3", 75), simple("PHYSICS 1D03", 74)],
}
H_STRONG = {
    "Fall 2024": [simple("COMPSCI 1MD3", 95), simple("MATH 1ZA3", 93), simple("PSYCH 1X03", 88)],
    "Winter 2025": [simple("MATH 1ZB3", 92), simple("PHYSICS 1D03", 91)],
    "Fall 2025": [simple("COMPSCI 2ME3", 94)],
}
# Long history: nine finished courses over four terms, including a Spring/Summer term, a 6-unit
# course, a 0-unit course (excluded from the GPA) and one marked not-in-GPA.
H_VETERAN = {
    "Fall 2023": [simple("COMPSCI 1MD3", 80), simple("MATH 1ZA3", 77), simple("CHEM 1A03", 74),
                  simple("ENGINEER 1EE0", 100)],
    "Winter 2024": [simple("MATH 1ZB3", 78), simple("PHYSICS 1D03", 76),
                    simple("ECON 1B03", 55, include_in_gpa=False)],
    "Spring/Summer 2024": [simple("ECON 1BB3", 81)],
    "Fall 2024": [simple("ARTSSCI 1D06 A/B", 79), simple("COMPSCI 2ME3", 80)],
    "Fall 2025": [simple("COMPSCI 2GA3", 78), simple("STATS 2D03", 75)],
}

CUR3 = ("COMPSCI 2C03", "COMPSCI 2DB3", "COMPSCI 2SD3")


def three(rows_by_course, grades_by_course):
    return [course(c, r, g) for c, r, g in zip(CUR3, rows_by_course, grades_by_course)]


def std3(*grades):
    return three([STD, STD, STD], grades)

# ---------------------------------------------------------------- accounts
#
# target_delta is added to the computed baseline to get the declared target, so a change to a
# history above does not silently move a GROWTH account into MAINTENANCE.

ACCOUNTS = [
    # ---- the top of the board
    dict(key="climber", username="tclimber", handle="SummitSeeker", avatar="1,3,2,1,4",
         note="GROWTH, target reached and saturated (A+ in everything). Top of the board.",
         history=H_STANDARD, target_delta="1.00",
         current=std3([92, 94, 95], [93, 91, 96], [90, 92, 94]),
         future={"Fall 2026": ["COMPSCI 3AC3", "SFWRENG 2AA4"]}),

    dict(key="ceiling", username="tceiling", handle="TopOfScale", avatar="0,6,0,3,2",
         note="CEILING mode: baseline above 11.0, so holding the level is a full 10,000.",
         history=H_STRONG, target_delta="0.50",
         current=std3([92, 94, 96], [90, 92, 94], [91, 93, 95])),

    dict(key="freshman", username="tfreshman", handle="FirstYearFlash", avatar="3,1,4,0,5",
         note="Baseline 0.00 (nothing on the transcript), measured from 6.0. A's against a "
              "9.0 target: a real climb with a 0.75 ambition bonus.",
         history={}, target_delta="9.00",
         current=[course("COMPSCI 1MD3", STD, [88, 86, 90]),
                  course("MATH 1ZA3", LABS, [80, 85, 90, 84, 88]),
                  course("BIOLOGY 1M03", FRACTIONS, [85, 85, 85, 85])],
         future={"Fall 2026": ["COMPSCI 2C03", "MATH 1ZB3"]}),

    dict(key="freshman.low", username="tfreshlow", handle="LowBarLarry", avatar="1,2,3,0,4",
         note="The old loophole: empty transcript, target 1.0, D- in everything. Used to "
              "score 10,000; now a Maintenance target measured from 6.0, so 0.",
         history={}, target_delta="1.00",
         current=[course("COMPSCI 1MD3", STD, [51, 50, 52]),
                  course("MATH 1ZA3", STD, [50, 52, 51]),
                  course("BIOLOGY 1M03", STD, [52, 51, 50])],
         future={"Fall 2026": ["COMPSCI 2C03"]}),

    # ---- exact ties, broken only by handle
    dict(key="tie-a", username="ttiea", handle="AlphaTwin", avatar="2,1,5,0,1",
         note="Tie pair. Same letters as BetaTwin from different percentages, so the score "
              "is identical and handle ASC decides who is above.",
         history=H_STANDARD, target_delta="1.00",
         current=std3([84, 86, 88], [86, 88, 87], [85, 87, 86])),

    dict(key="tie-b", username="ttieb", handle="BetaTwin", avatar="3,5,1,2,0",
         note="Tie pair, second half.",
         history=H_STANDARD, target_delta="1.00",
         current=std3([90, 85, 86], [88, 84, 89], [86, 85, 88])),

    # A three-way tie whose order depends on the database collation: case-insensitive
    # (English_Canada / en_US) gives Delta, echo, Foxtrot; byte order (C) gives Delta, Foxtrot, echo.
    dict(key="tie.delta", username="ttiedelta", handle="Delta", avatar="4,0,3,1,2",
         note="Three-way tie, case-sensitivity probe.",
         history=H_STANDARD, target_delta="1.00",
         current=std3([80, 80, 80], [80, 80, 80], [80, 80, 80])),
    dict(key="tie.echo", username="ttieecho", handle="echo", avatar="5,2,6,2,3",
         note="Three-way tie, lowercase handle.",
         history=H_STANDARD, target_delta="1.00",
         current=std3([81, 79, 81], [82, 80, 80], [79, 81, 80])),
    dict(key="tie.foxtrot", username="ttiefoxtrot", handle="Foxtrot", avatar="0,4,1,3,4",
         note="Three-way tie, uppercase F.",
         history=H_STANDARD, target_delta="1.00",
         current=std3([83, 78, 80], [80, 81, 80], [80, 80, 81])),

    # ---- behaviour damping
    dict(key="clustered", username="tclustered", handle="BusyWeek", avatar="4,2,6,1,5",
         note="Same letters as the tie pair, damped by one clustering flag (x0.9).",
         history=H_STANDARD, target_delta="1.00",
         current=three([CLUSTERED, STD, STD], [[87, 87, 87, 87], [86, 87, 87], [86, 86, 86]])),

    dict(key="churner", username="tchurner", handle="DateShifter", avatar="2,3,3,2,1",
         note="Clustering plus due-date churn after grading: two flags (x0.8).",
         history=H_STANDARD, target_delta="1.00",
         current=three([CLUSTERED, STD, STD], [[87, 87, 87, 87], [86, 87, 87], [86, 86, 86]]),
         churn=2),

    # ---- holding modes and the bottom of the board
    dict(key="maintain", username="tmaintain", handle="SteadyHand", avatar="5,4,3,0,3",
         note="MAINTENANCE mode: target under a point above baseline, so a 7,000 ceiling.",
         history=H_STANDARD, target_delta="0.50",
         current=std3([80, 81, 82], [82, 80, 81], [81, 81, 81])),

    dict(key="slipping", username="tslipping", handle="RecoveryArc", avatar="2,7,7,3,0",
         note="Fell below baseline. Scores inside the recovery band only (under 1,000).",
         history=H_STANDARD, target_delta="1.00",
         current=std3([62, 60, 64], [66, 63, 65], [64, 62, 66])),

    dict(key="freefall", username="tfreefall", handle="RockBottom", avatar="1,6,0,0,0",
         note="More than 2.0 below baseline: score 0.00 but still ACTIVE and on the board.",
         history=H_STRONG, target_delta="0.50",
         current=std3([55, 52, 54], [50, 48, 52], [58, 55, 51])),

    # ---- people who did the work but sit on the 1,000 floor anyway
    dict(key="solo", username="tsolo", handle="QuietStart", avatar="1,0,4,2,1",
         note="Enrolled, nothing graded yet. Sits on the 1,000 floor.",
         history=H_STANDARD, target_delta="2.00",
         current=std3([None] * 3, [None] * 3, [None] * 3)),

    dict(key="halfway", username="thalfway", handle="Halfway_There", avatar="3,3,5,1,2",
         note="Every final exam left blank. A course missing any non-bonus grade scores "
              "nothing, so strong midterms are worth 1,000.",
         history=H_STANDARD, target_delta="1.00",
         current=std3([95, 96, None], [94, 97, None], [93, 95, None])),

    dict(key="latecomer", username="tlatecomer", handle="BackfillBen", avatar="0,1,2,3,4",
         note="A full table of A+ marks, all entered 20 days after the due date (window is 14). "
              "None of it counts.",
         history=H_STANDARD, target_delta="1.00",
         current=std3([95, 96, 97], [94, 97, 98], [96, 95, 99]), lag_override=20),

    dict(key="badweights", username="tbadweights", handle="NinetyPercent", avatar="4,5,0,2,3",
         note="One course's weights sum to 90, so that course is dropped as malformed; the "
              "other two still count.",
         history=H_STANDARD, target_delta="1.00",
         current=three([SHORT_WEIGHTS, STD, STD], [[95, 95, 95], [86, 87, 87], [86, 86, 86]])),

    # ---- calendar and layout stress
    dict(key="longnames", username="tlongnames", handle="TheExtremelyLongHandle24",
         avatar="5,7,7,3,5",
         note="Maximum-length handle. LONG_NAMES course: 100 to 180 character assessment "
              "names, an unbroken 90-character token, a long location, a recurring due date "
              "(dropped from the calendar) and a blank bonus row. Plus a two-scheme course.",
         history=H_VETERAN, target_delta="1.00",
         current=[course("COMPSCI 2C03", LONG_NAMES, [88, 91, 85, 90, None, 87]),
                  multi("COMPSCI 2DB3", ("Scheme A: Midterm Counts", ALT_A, [80, 62, 84]),
                        ("Scheme B: Final Replaces Midterm", ALT_B, [80, 62, 84])),
                  course("SOCWORK 4Y03", FRACTIONS, [82, 84, 86, 85]),
                  course("COMPSCI 2SD3", STD, [84, 85, 86])],
         future={"Fall 2026": ["COMPSCI 4ZP6 A/B", "COMPSCI 3AC3"]}),

    dict(key="crammed", username="tcrammed", handle="Kai", avatar="2,0,0,1,1",
         note="Three-character handle. Six courses with something due on the same day "
              "(term day 66), to see how a calendar cell copes. Spread across courses, so no "
              "clustering flag.",
         history=H_VETERAN, target_delta="1.00",
         current=[course(c, [("Assignment 1", 20, 30, 4, {}),
                             (f"{c.split()[0].title()} Midterm Report", 30, 66, 5, {}),
                             ("Final Exam", 50, 100, 5, {})], g)
                  for c, g in (("COMPSCI 2C03", [86, 88, 90]), ("COMPSCI 2DB3", [84, 85, 86]),
                               ("COMPSCI 2SD3", [88, 87, 89]), ("LIFESCI 1D03", [82, 84, 83]),
                               ("ECON 1B03", [90, 91, 92]), ("KINESIOL 1A03", [85, 85, 85]))]),

    # ---- not on the board at all
    dict(key="withdrawn", username="twithdrawn", handle="SteppedAway", avatar="0,2,1,1,2",
         note="Joined, then WITHDREW with a top-tier table. Must not appear on the board.",
         history=H_STANDARD, target_delta="1.00", status="WITHDRAWN",
         current=std3([92, 94, 95], [93, 91, 96], [90, 92, 94])),
]

# ---------------------------------------------------------------- building rows


def assessment_rows(term, rows, grades, now, lag_override=None, churn=0):
    """The JSON the converter would have written: camelCase, grades encrypted, ISO instants."""
    out = []
    for i, ((name, weight, offset, lag, extra), grade) in enumerate(zip(rows, grades)):
        due = on(term, offset)
        if extra.get("recurring"):
            due = ", ".join(on(term, offset + 7 * k) for k in range(extra["recurring"]))
        lag = lag_override if lag_override is not None else lag
        graded_at = datetime.fromisoformat(on(term, offset)).replace(
            hour=15, tzinfo=timezone.utc) + timedelta(days=lag)
        if graded_at > now:
            grade = None   # a future assessment cannot have a mark the server saw appear
        row = {
            "name": name,
            "description": "",
            "location": extra.get("location", ""),
            "dueDate": due,
            "startTime": extra.get("startTime", ""),
            "endTime": extra.get("endTime", ""),
            "weight": weight,
            "grade": encrypt(Decimal(str(float(grade)))) if grade is not None else None,
            "firstGradedAt": None,
            "lastGradedAt": None,
            # Churn counts due-date moves made after the grade existed; spread over the first rows.
            "dueDateChangeCount": churn if (grade is not None and i < 3) else 0,
        }
        if grade is not None:
            stamp = graded_at.isoformat().replace("+00:00", "Z")
            row["firstGradedAt"] = stamp
            row["lastGradedAt"] = stamp
        out.append(row)
    return out


def client_percentage(rows, grades):
    """What the client shows as the course grade: earned over graded weight."""
    earned = weight_sum = 0.0
    for (_, w, _, _, _), g in zip(rows, grades):
        if g is None or w == 0:
            continue
        earned += w * g
        weight_sum += w
    return None if weight_sum == 0 else round(earned / weight_sum, 2)


def course_ids(cur, codes):
    cur.execute("select course_code, id, course_credits from course where course_code = any(%s)",
                (list(codes),))
    return {code: (cid, credits) for code, cid, credits in cur.fetchall()}


def auth_call(method, path, **kwargs):
    """
    One call to an /auth endpoint, waiting out RateLimitFilter.

    The filter allows 10 requests per minute per IP across /login, /register and /verify
    together, and seeding needs two per new account, so a fresh run will trip it.
    """
    for _ in range(12):
        r = requests.request(method, f"{BASE_URL}{path}", timeout=30, **kwargs)
        if r.status_code != 429:
            return r
        print(f"    rate limited on {path}, waiting for the window to reset", flush=True)
        time.sleep(20)
    raise SystemExit(f"{path} still rate limited after several attempts")


def register(email, username):
    """Register, verify, and return the student id. Skips work already done."""
    # Asked of the database first, not of /register: the endpoint answers an existing email
    # with a 500, so a re-run could not tell "already seeded" apart from a real failure.
    with psycopg2.connect(**PG) as conn, conn.cursor() as cur:
        cur.execute("select id from users where email=%s", (email,))
        existing = cur.fetchone()

    if existing is None:
        r = auth_call("POST", "/register",
                      json={"email": email, "username": username, "password": PASSWORD})
        if r.status_code != 201:
            raise SystemExit(f"register {email} failed: {r.status_code} {r.text[:200]}")

    with psycopg2.connect(**PG) as conn, conn.cursor() as cur:
        cur.execute("select id, email_verified from users where email=%s", (email,))
        user_id, verified = cur.fetchone()
        token = None
        if not verified:
            cur.execute("select token from secure_token where user_id=%s order by id desc limit 1",
                        (user_id,))
            token = cur.fetchone()[0]

    if token:
        v = auth_call("POST", "/verify", json={"token": token})
        if v.status_code != 200:
            raise SystemExit(f"verify {email} failed: {v.status_code} {v.text[:200]}")

    with psycopg2.connect(**PG) as conn, conn.cursor() as cur:
        cur.execute("select id from student where user_id=%s", (user_id,))
        row = cur.fetchone()
        if row is None:
            raise SystemExit(f"no student row for {email} after verification")
    return row[0], existing is None


def verify_key(cur):
    """
    Proves the key round-trips against a row the application itself wrote.

    Without this the first symptom of a wrong key is a 500 from /login, because the Student
    entity decrypts its GPA columns on load.
    """
    seeded = [a["username"] for a in ACCOUNTS]
    cur.execute("""select s.gpa12 from student s join users u on u.id = s.user_id
                   where s.gpa12 is not null and u.username <> all(%s)""", (seeded,))
    candidates = [r[0] for r in cur.fetchall()]
    for value in candidates:
        try:
            print(f"key check: an existing student GPA decrypts to {decrypt(value)}")
            return
        except Exception:
            continue   # one legacy row holds the non-AES literal "enc:9"
    if candidates:
        raise SystemExit("the resolved GRADE_ENCRYPTION_KEY decrypted none of the existing "
                         "student GPA rows; seeding with it would produce rows that 500 on read")
    print("no pre-existing encrypted row to check the key against; continuing")


def email_for(acct):
    return f"{acct['key'].replace('-', '.')}@mcmaster.ca"


def main():
    now = datetime.now(timezone.utc)
    print(f"current term: {CURRENT_TERM}")

    codes = set()
    for a in ACCOUNTS:
        codes |= {c["code"] for cs in a["history"].values() for c in cs}
        codes |= {c["code"] for c in a["current"]}
        codes |= {c for cs in a.get("future", {}).values() for c in cs}

    conn = psycopg2.connect(**PG)
    cur = conn.cursor()
    verify_key(cur)
    catalog = course_ids(cur, codes)
    missing = codes - set(catalog)
    if missing:
        raise SystemExit(f"courses missing from the catalog: {sorted(missing)}")
    conn.commit()

    report = []
    for acct in ACCOUNTS:
        email = email_for(acct)
        student_id, created = register(email, acct["username"])

        # ----- wipe anything a previous run seeded for this student
        for table in ("leaderboard_rank_history", "leaderboard_entry"):
            cur.execute(f"delete from {table} where student_id=%s", (student_id,))
        for table in ("leaderboard_profile", "season_baseline", "transcript_upload",
                      "past_course", "course_enrollement", "assessment_table", "calendar_events"):
            cur.execute(f"delete from {table} where student_id=%s", (student_id,))

        def enrol(term, code, grade, include=True):
            cur.execute("""insert into course_enrollement
                             (student_id, course_id, term, grade, include_in_gpa,
                              created_at, updated_at)
                           values (%s,%s,%s,%s,%s,%s,%s)""",
                        (student_id, catalog[code][0], term,
                         encrypt(grade) if grade is not None else None, include, now, now))

        def table(term, c):
            schemes = []
            for name, rows, grades in c["schemes"]:
                schemes.append({"schemeName": name, "assessments": assessment_rows(
                    term, rows, grades, now, acct.get("lag_override"), acct.get("churn", 0))})
            cur.execute("""insert into assessment_table
                             (id, student_id, course_code, term, schemes, created_at, updated_at)
                           values (%s,%s,%s,%s,%s::jsonb,%s,%s)""",
                        (str(uuid.uuid4()), student_id, c["code"], term,
                         json.dumps(schemes), now, now))

        # ----- past terms: picker entries, read-only tables, and the transcript
        transcript = []
        for term in sorted(acct["history"], key=term_key):
            for c in acct["history"][term]:
                _, rows, grades = c["schemes"][0]
                pct = client_percentage(rows, grades)
                include = c.get("include_in_gpa", True)
                enrol(term, c["code"], pct, include)
                table(term, c)
                if include:
                    transcript.append((c["code"], to_letter(pct), str(catalog[c["code"]][1])))
        for code, letter, units in transcript:
            cur.execute("""insert into past_course (student_id, name, grade, units,
                                                    created_at, updated_at)
                           values (%s,%s,%s,%s,%s,%s)""",
                        (student_id, code, encrypt(letter), units, now, now))
        baseline = gpa([(l, u) for _, l, u in transcript], MAC)
        target = min(baseline + Decimal(acct["target_delta"]), Decimal("12.00"))

        # ----- current term: editable, scored
        dashboard_pairs = [(l, u) for _, l, u in transcript]
        for c in acct["current"]:
            _, rows, grades = c["schemes"][0]
            visible = [None if (datetime.fromisoformat(on(CURRENT_TERM, r[2])).replace(
                tzinfo=timezone.utc) + timedelta(days=r[3])) > now else g
                for r, g in zip(rows, grades)]
            pct = client_percentage(rows, visible)
            enrol(CURRENT_TERM, c["code"], pct)
            table(CURRENT_TERM, c)
            if pct is not None:
                dashboard_pairs.append((to_letter(pct), str(catalog[c["code"]][1])))

        # ----- future terms: enrolled, nothing else
        for term, future_codes in acct.get("future", {}).items():
            for code in future_codes:
                enrol(term, code, None)

        # ----- dashboard GPAs (what the client would have saved)
        dash12 = gpa(dashboard_pairs, MAC)
        dash4 = gpa(dashboard_pairs, FOUR)
        cur.execute("""update student set gpa12=%s, gpa4=%s, target_gpa12=%s, target_gpa4=%s,
                              updated_at=%s where id=%s""",
                    (encrypt(dash12), encrypt(dash4), encrypt(target),
                     encrypt(four_point(target)), now, student_id))

        # ----- the transcript upload the baseline came from, and the frozen season
        digest = hashlib.sha256(f"seed::{acct['key']}::{CURRENT_TERM}".encode()).hexdigest()
        joined = now - timedelta(days=40)
        cur.execute("""insert into transcript_upload (student_id, season, file_sha256,
                         parsed_gpa12, uploaded_at, created_at, updated_at)
                       values (%s,%s,%s,%s,%s,%s,%s)""",
                    (student_id, CURRENT_TERM, digest, encrypt(baseline), joined, joined, now))
        mode = mode_for(float(baseline), float(target))
        cur.execute("""insert into season_baseline (student_id, season, baseline_gpa12,
                         target_gpa12, score_mode, created_at, updated_at)
                       values (%s,%s,%s,%s,%s,%s,%s)""",
                    (student_id, CURRENT_TERM, encrypt(baseline), encrypt(target), mode,
                     joined, now))
        cur.execute("""insert into leaderboard_profile (student_id, handle, avatar, opted_in_at,
                         rules_accepted_at, created_at, updated_at)
                       values (%s,%s,%s,%s,%s,%s,%s)""",
                    (student_id, acct["handle"], acct["avatar"], joined, joined, joined, now))
        cur.execute("""insert into leaderboard_entry (student_id, season, handle, avatar, score,
                         status, score_mode, ambition, behavior_score, behavior_flags,
                         created_at, updated_at)
                       values (%s,%s,%s,%s,0,%s,%s,0,1,0,%s,%s) returning id""",
                    (student_id, CURRENT_TERM, acct["handle"], acct["avatar"],
                     acct.get("status", "ACTIVE"), mode, joined, now))

        report.append(dict(acct=acct, email=email, student_id=student_id, mode=mode,
                           baseline=baseline, target=target, created=created,
                           terms=len(acct["history"]) + 1 + len(acct.get("future", {}))))

    # ----- rank history, so sparklines and movement chips have something to draw.
    # Six samples eight hours apart, all older than RANK_COOLDOWN so the recompute still ranks.
    # The sequence is a deterministic shuffle of seed order: some students end up climbing,
    # some falling, some flat.
    active = [r for r in report if r["acct"].get("status", "ACTIVE") == "ACTIVE"]
    n = len(active)
    for idx, r in enumerate(active):
        samples = []
        for s in range(6):
            drift = ((idx * 7 + s * (idx % 3 - 1) * 2) % n) + 1
            samples.append(drift)
        for s, rank in enumerate(samples):
            at = now - timedelta(hours=8 * (6 - s)) - timedelta(minutes=30)
            cur.execute("""insert into leaderboard_rank_history (student_id, season, rank, score,
                             computed_at) values (%s,%s,%s,0,%s)""",
                        (r["student_id"], CURRENT_TERM, rank, at))
        cur.execute("update leaderboard_entry set rank=%s where student_id=%s and season=%s",
                    (samples[-1], r["student_id"], CURRENT_TERM))

    # The non-seeded students in this season keep their own history, but any sample from the last
    # ten minutes would trip assignRanks' cooldown and leave the new entries unranked.
    cur.execute("""delete from leaderboard_rank_history
                   where season=%s and computed_at > now() - interval '15 minutes'""",
                (CURRENT_TERM,))

    conn.commit()
    cur.close()
    conn.close()

    # ----- let the application score and rank the board
    r = requests.post(f"{BASE_URL}/internal/jobs/leaderboard-recompute",
                      headers={"X-Jobs-Token": JOBS_TOKEN}, timeout=120)
    print(f"recompute -> {r.status_code} {r.text}\n")
    if r.status_code != 200:
        raise SystemExit("the recompute did not succeed, so scores and ranks are not seeded")

    with psycopg2.connect(**PG) as conn, conn.cursor() as cur:
        cur.execute("""select student_id, score, rank, previous_rank, behavior_flags
                       from leaderboard_entry where season=%s""", (CURRENT_TERM,))
        live = {sid: rest for sid, *rest in cur.fetchall()}

    print(f"{'email':<27} {'handle':<25} {'mode':<11} {'base':>5} {'targ':>5} "
          f"{'score':>9} {'rank':>4} {'prev':>4} fl terms")
    for row in sorted(report, key=lambda x: (live[x["student_id"]][1] or 999)):
        score, rank, prev, flags = live[row["student_id"]]
        print(f"{row['email']:<27} {row['acct']['handle']:<25} {row['mode']:<11} "
              f"{row['baseline']:>5} {row['target']:>5} {score:>9} {str(rank or '-'):>4} "
              f"{str(prev or '-'):>4} {flags:>2} {row['terms']:>5}")
    print(f"\npassword for every account: {PASSWORD}")


if __name__ == "__main__":
    sys.exit(main())
