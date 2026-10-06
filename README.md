# CampusBalance

Student work-life balance analytics. Students log their coursework, side commitments, recovery
activities and daily wellness; CampusBalance turns that into a **Workload Index**, a **Balance
Score** and a **burnout-risk forecast**. Faculty see a stress heatmap of their class, and admins
see institution-wide semester reports.

**Live demo:** https://campusbalance-ghna.onrender.com
(free hosting — the first visit after ~15 idle minutes takes 30–60 seconds to wake the server)

| Who | Login page | Demo account |
|---|---|---|
| Student | [`/login.html`](https://campusbalance-ghna.onrender.com/login.html) | `aadhya.reddy` / `Campus@123` |
| Faculty | [`/staff-login.html`](https://campusbalance-ghna.onrender.com/staff-login.html) | `anika.verma` / `Campus@123` |
| Admin | [`/staff-login.html`](https://campusbalance-ghna.onrender.com/staff-login.html) | private |

The demo database holds **synthetic** data (150 generated students) — see [Demo data](#demo-data).

## Features

**Students**
- Semesters with a start/end date; a semester archives itself when it ends and stays readable in Profile / History
- Subjects with a 2-week **calibration phase** that produces a per-subject Difficulty Coefficient (D<sub>s</sub>)
- Assignments with deadlines, priorities and a calendar view; +50 XP per submitted assignment
- Focus activities (add stress) and Recovery activities (relieve it), each pausable
- Daily wellness check-in (sleep, study, mood, energy); +20 XP and a daily streak
- Insights: 14-day Balance Score trend, 7-day forecast, workload breakdown and recommendations
- **Burnout signals** — five rule-based classes, each with the reason it fired, mapped to an intervention tier:

  | Class | Fires when | Tier |
  |---|---|---|
  | PHY Physical Exhaustion | 3 of the last 5 check-ins (last 10 days) had under 5 h sleep or energy ≤ 2 | 2 |
  | EMO Emotional Exhaustion | more than half of the last 7 days' check-ins (min. 3) were "Stressed" | 2 |
  | COG Cognitive Overload | coursework load ≥ 30 (half of capacity) and ≥ 3× recovery relief | 1 |
  | ORG Organizational Issues | Workload % jumped 50+ points between neighbouring days (last 14 days) | 1 |
  | CTRL Loss of Control | Sustained Overload: Workload % > 85 on 3 consecutive days | 3 |

- **Tiered interventions** — Tier 1: targeted nudges · Tier 2: an opt-in 4-week coping plan (sleep or stress) with weekly
  goals tracked from check-ins · Tier 3: a counsellor referral (routine tips are suppressed) plus a printable
  **summary report** the student can share

**Faculty** — class stress heatmap (filterable by department) and a high-risk watchlist sorted by intervention tier,
with each student's burnout signals and 14-day Balance Score trend

**Admin** — creates faculty accounts; semester report (risk distribution, averages) and department comparison

**Everyone** — change your own password from the sidebar; works on phones (collapsible menu)

## How the scores work

| Quantity | Formula |
|---|---|
| Difficulty Coefficient | D<sub>s</sub> = (week-1 difficulty + week-2 difficulty + average weekly study hours) / credits |
| Coursework load | each pending assignment ≈ 3 h × D<sub>s</sub> (or × its Easy 1 / Medium 3 / Hard 5 weight before calibration finishes) |
| Workload Index | W<sub>i</sub> = (coursework + active focus weights − active recovery weights) × mood coefficient (Good 0.8, Neutral 1.2, Stressed 1.5) |
| Balance Score | B = 100 − W<sub>i</sub> / 60 h × 100 + recovery bonus (sleep ≥ 7 h: +10, sleep < 5 h: −15, energy ≥ 4: +5), clamped to 0–100 |
| Burnout risk | B < 40 → HIGH, B < 70 → MEDIUM, otherwise LOW |
| Forecast | linear regression over the last 14 daily scores, projected 7 days ahead |
| Sustained overload | workload above 85 % of capacity on 3 consecutive days |

## Tech stack

Java 17 · Spring Boot 3.5 (Web, Security with JWT, Data MongoDB, Validation) · MongoDB Atlas ·
plain HTML/CSS/JavaScript with Chart.js · Docker · Render

The Spring Boot app serves both the REST API (`/api/...`) and the web pages, so it deploys as a
single service.

```
CampusBalanceFinal/
├── analytics-backend/analytics/         Spring Boot app
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/campusbalance/analytics/
│       │   ├── controller/   REST endpoints + error handling
│       │   ├── service/      scoring, semesters, calibration, analytics
│       │   ├── security/     JWT login tokens and access rules
│       │   ├── model/ dto/ repository/
│       │   └── migration/    one-time data fixes run at startup
│       └── resources/static/ the web pages (login, student, faculty, admin)
├── render.yaml                          Render Blueprint
├── seed_data.py                         demo data generator
├── list_accounts.py                     exports all demo accounts to CSV
└── .env.example                         template for local secrets
```

## Run it locally

Needs Java 17, Maven and a MongoDB database (a free MongoDB Atlas cluster works).

1. Copy `.env.example` to `.env` in the project root and fill in `MONGODB_URI` and `JWT_SECRET`.
   `.env` is git-ignored; the app reads it automatically when run locally.
2. Start the app:
   ```bash
   cd analytics-backend/analytics
   mvn spring-boot:run
   ```
3. Open http://localhost:8080/login.html
4. Create the first admin account from the **First-time setup** box on
   http://localhost:8080/staff-login.html — it locks itself once an admin exists. Faculty accounts
   are then created from the admin dashboard.

### Tests

```bash
cd analytics-backend/analytics
mvn test
```

The tests use mocks and never connect to a database.

## Deploy to Render

1. In MongoDB Atlas → **Network Access**, allow `0.0.0.0/0` (Render's free servers have no fixed IP).
2. In Render: **New → Web Service**, pick this repo, and set:
   - Language **Docker**, Root Directory `analytics-backend/analytics`, Instance Type **Free**
   - Environment variables `MONGODB_URI` (your Atlas connection string) and `JWT_SECRET`
     (any random string of 32+ characters)

   Alternatively use **New → Blueprint**, which reads [`render.yaml`](render.yaml).
3. Every push to `main` redeploys automatically.

## Demo data

```bash
pip install requests pymongo
python seed_data.py      # backend must be running; reads MONGODB_URI from .env
python list_accounts.py  # writes campusbalance_accounts.csv (git-ignored)
```

`seed_data.py` tops the student count up to `TARGET_TOTAL_STUDENTS` (150) through the real API,
then writes 30 days of backdated wellness and Balance Score history for any student without
history. Seeded accounts use the password `Campus@123`. **This data is synthetic — don't
present it as real student results.**

## API

All `/api` routes except login/sign-up need an `Authorization: Bearer <token>` header from
`POST /api/login`. Routes with `{username}` only accept the logged-in user's own username.

| Access | Endpoints |
|---|---|
| Public | `POST /api/login` · `POST /api/register` · `GET /api/admin-exists` · `POST /api/register-admin` (only until an admin exists) |
| Any logged-in account | `POST /api/change-password` |
| Student (own data) | `GET /api/dashboard/{username}` · `GET /api/insights/{username}` · `GET, POST /api/semesters/{username}` · `POST /api/end-semester/{username}` · `GET, POST /api/subjects/{username}` · `POST /api/calibration/{username}` · `POST /api/focus-activity/{username}` · `POST /api/recovery-activity/{username}` · `POST /api/toggle-focus-activity/{username}?name=` · `POST /api/toggle-recovery-activity/{username}?name=` · `POST /api/wellness/{username}` · `POST /api/assignments/{username}` · `POST /api/submit-task/{username}?id=` · `POST /api/coping-plan/{username}` · `POST /api/end-coping-plan/{username}` · `GET /api/report/{username}` |
| Faculty, Admin | `GET /api/faculty/heatmap?department=` · `GET /api/faculty/high-risk` |
| Admin | `POST /api/admin/create-faculty` · `GET /api/admin/report/{semester}` · `GET /api/admin/trends` |

Errors come back as a plain-text message with a 400 (invalid input), 401 (not logged in),
403 (not allowed), 404 or 409 (conflict, e.g. already checked in today) status.
