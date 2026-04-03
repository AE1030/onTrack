<p align="center">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot" />
  <img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white" alt="Java" />
  <img src="https://img.shields.io/badge/Expo-SDK%2054-000020?logo=expo&logoColor=white" alt="Expo" />
  <img src="https://img.shields.io/badge/React%20Native-0.81-61DAFB?logo=react&logoColor=white" alt="React Native" />
  <img src="https://img.shields.io/badge/Firebase-Hosting-FFCA28?logo=firebase&logoColor=black" alt="Firebase" />
  <img src="https://img.shields.io/badge/Google%20Cloud-Run-4285F4?logo=googlecloud&logoColor=white" alt="Cloud Run" />
</p>

# onTrack

**onTrack** is an academic progress tracker built for McMaster University students. Upload your transcript, track your GPA, manage course assessments, and sync deadlines to Google Calendar — all in one place.

> **Live at [ontrackmac.ca](https://ontrackmac.ca)**

---

## Features

### Transcript Upload
Upload your McMaster transcript as a PDF and onTrack automatically parses it to import your past courses, grades, and current term enrollments. Supports multi-year courses (e.g., `ENGINEER 1P13 A/B`) and handles duplicate detection.

### GPA Tracking
View your cumulative GPA on both the **4.0** and **12.0** scales. Set a target GPA and visualize your progress. GPA is recalculated automatically whenever courses or grades change.

### AI-Powered Syllabus Extraction
Upload a course syllabus PDF and onTrack uses **Google Gemini 2.5 Pro** to extract the full assessment breakdown — names, weights, due dates, and grading schemes. Supports courses with multiple grading schemes and selection rules (e.g., "best of" schemes). Extraction runs asynchronously with real-time job status polling.

### Assessment Table
View and edit assessment details for each course: weights, due dates, times, locations, and grades. Tables are automatically populated from uploaded syllabi or can be built manually.

### Course Grade Calculator
Enter grades for individual assessments and see your projected final course grade, calculated from syllabus weight breakdowns across all applicable grading schemes.

### Google Calendar Sync
Connect your Google account and export upcoming assessment deadlines to Google Calendar. Uses a **diff-based sync algorithm** — re-syncing only adds new events and removes outdated ones, never creating duplicates.

### Dashboard
An at-a-glance overview of your current GPA vs. target, upcoming deadlines, and active courses. Quick actions to add assignments, sync your calendar, or report a bug.

### Course Search
Browse McMaster's course catalogue to look up course details and descriptions.

### My Courses
Manage your current term courses, upload syllabi, and access per-course assessment tables.

---

## Security

Protecting student data is a priority. Here's how onTrack handles security:

| Measure | Details |
|---------|---------|
| **Authentication** | Stateless JWT (HMAC-SHA, 1-hour expiry). No server-side sessions. |
| **Password Storage** | BCrypt with strength factor 12. Passwords are never stored in plaintext. |
| **Password Requirements** | Minimum 8 characters with at least one uppercase letter, one lowercase letter, and one digit. |
| **Grade Encryption** | All grade data is encrypted at rest using **AES-256-GCM** with per-record initialization vectors. |
| **Email Verification** | Token-based verification required before account activation. Tokens are cryptographically random (32 bytes) with a 15-minute expiry and single-use enforcement. |
| **Rate Limiting** | Sliding-window rate limiter on all endpoints — 10 req/min on authentication routes, 60 req/min on general routes. |
| **OAuth 2.0** | Google Calendar integration uses JWT-signed state tokens (5-minute expiry) to prevent CSRF during the OAuth flow. |
| **Input Validation** | Server-side validation on all user inputs with a global exception handler that returns structured error responses. |
| **Error Handling** | Stack traces, exception types, and internal error messages are never exposed to clients. |
| **McMaster-Only Registration** | Only `@mcmaster.ca` email addresses can register. |
| **CORS** | Restricted to configured frontend origin with credentials support. |
| **Infrastructure** | Hosted on Google Cloud (northamerica-northeast2) with Cloud SQL (PostgreSQL) and MongoDB Atlas. Backend deployed via Cloud Run with containerized builds. |

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| **Frontend** | React Native, Expo SDK 54, expo-router, react-native-reanimated |
| **Web Hosting** | Firebase Hosting |
| **Backend** | Spring Boot 3.5, Java 21 |
| **Databases** | PostgreSQL (Google Cloud SQL), MongoDB Atlas |
| **AI** | Google Gemini 2.5 Pro |
| **Auth** | JWT + BCrypt + email verification |
| **Email** | Resend |
| **CI/CD** | GitHub Actions |
| **Infrastructure** | Google Cloud Run, Firebase Hosting |

---

## Project Structure

```
onTrack/
├── backend/                        # Spring Boot REST API
│   └── src/main/java/.../
│       ├── accounts/               # Student profiles, transcript upload, course management
│       ├── assessmenttable/        # Assessment table CRUD and syllabus integration
│       ├── calendar/               # Google Calendar OAuth and diff-based sync
│       ├── courses/                # Course catalogue search
│       ├── mailing/                # Email verification and password reset emails
│       ├── security/               # JWT auth, rate limiting, encryption, user management
│       └── syllabus/               # Syllabus upload, Gemini AI extraction, grading schemes
├── frontend/                       # Expo React Native app
│   ├── app/                        # expo-router routes
│   ├── screens/                    # Screen components
│   ├── src/                        # Theme, config, utilities
│   └── public/                     # Landing page, privacy policy
└── .github/workflows/              # CI/CD pipelines
```

---

## Deployment

| Component | Pipeline | Target |
|-----------|----------|--------|
| **Backend** | Push to `main` (backend changes) | Docker build → Google Cloud Run |
| **Frontend** | Push to `main` (frontend changes) | Expo web export → Firebase Hosting |

Both pipelines run automatically via GitHub Actions. All secrets are stored in GitHub Secrets — no credentials are committed to the repository.

---

## Acknowledgements

- [macgrades/transcript-api](https://github.com/macgrades/transcript-api) — McMaster transcript parsing API that inspired the transcript upload feature.

---

## Contact

**Ahmed Elmanufi**

[![LinkedIn](https://img.shields.io/badge/LinkedIn-0A66C2?logo=linkedin&logoColor=white)](https://www.linkedin.com/in/ahmed-elmanufi-866906283) [![Email](https://img.shields.io/badge/Email-elmanufa%40mcmaster.ca-EA4335?logo=gmail&logoColor=white)](mailto:elmanufa@mcmaster.ca)
