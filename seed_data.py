"""
CampusBalance demo data seeder
================================
Fills the database with realistic-looking DEMO data. Safe to re-run: it only adds
what is missing.

  1. Tops the number of student accounts up to exactly TARGET_TOTAL_STUDENTS.
     New students are created the "honest" way, through the real backend API
     (so passwords are hashed, IDs are generated, and every business rule still
     applies): a running semester, subjects, completed 2-week calibration,
     focus/recovery activities and assignments.

  2. Gives every student who has no Balance Score history HISTORY_DAYS days of
     random history (WellnessLog + analytics_results), so the Insights trend
     charts, faculty heatmap and admin reports have data for everyone. This part
     writes *directly into MongoDB*, because the backend deliberately refuses to
     backdate a log or snapshot (it always stamps "today").

  3. Optionally creates NUM_FACULTY_TO_ADD faculty accounts (needs the admin login).

IMPORTANT - this is DEMO/TEST data for showing a working, populated app. It is
not real student data and should never be described as real results in a
research paper -- keep that synthetic distinction clear.

---------------------------------------------------------------------------
SETUP (one-time):
    pip install requests pymongo

BEFORE running:
    - The backend must be running (locally: http://localhost:8080).
    MONGODB_URI      -> put your MongoDB Atlas connection string in the
                        git-ignored .env file next to this script (see
                        .env.example). Never paste it into this file.
    CAMPUSBALANCE_API -> optional, in .env or your environment. The backend URL
                        to seed through. Defaults to http://localhost:8080.
    SEED_ADMIN_USERNAME / SEED_ADMIN_PASSWORD
                     -> your admin login, in .env. Only needed when
                        NUM_FACULTY_TO_ADD > 0 (only admins may create faculty).

RUN:
    python seed_data.py
---------------------------------------------------------------------------
"""

import os
import random
import sys
import time
from datetime import date, timedelta
from pathlib import Path
from urllib.parse import quote_plus

import requests
from pymongo import MongoClient


def load_env_file():
    """Reads KEY=VALUE lines from the git-ignored .env next to this script into
    os.environ, without overriding variables that are already set."""
    env_path = Path(__file__).with_name(".env")
    if not env_path.exists():
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


load_env_file()

# ============================================================================
# CONFIG -- secrets come from .env / environment variables, never hard-coded here
# ============================================================================
BASE_URL = os.environ.get("CAMPUSBALANCE_API", "http://localhost:8080").rstrip("/")

# Your MongoDB Atlas connection string. You do NOT need to URL-encode special
# characters in the password -- the script re-encodes it safely before use.
MONGODB_URI = os.environ.get("MONGODB_URI", "")

TARGET_TOTAL_STUDENTS = 150       # student accounts are topped up to exactly this many
NUM_FACULTY_TO_ADD = 0            # extra faculty accounts to create on this run
HISTORY_DAYS = 30                 # days of Balance Score history for students who have none
DEFAULT_PASSWORD = "Campus@123"   # every seeded account uses this password
DB_NAME = "campusbalance"         # change if your Atlas database has a different name

# ============================================================================
# Reference data pools
# ============================================================================
FIRST_NAMES = [
    "Aarav", "Vivaan", "Aditya", "Vihaan", "Arjun", "Sai", "Reyansh", "Ayaan",
    "Krishna", "Ishaan", "Rohan", "Kabir", "Dhruv", "Aryan", "Karthik", "Yash",
    "Ananya", "Diya", "Saanvi", "Aadhya", "Kiara", "Myra", "Anika", "Navya",
    "Riya", "Ira", "Pari", "Sara", "Trisha", "Meera", "Tanvi", "Ishita",
    "Rahul", "Vikram", "Nikhil", "Siddharth", "Varun", "Aman", "Harsh", "Raj",
    "Priya", "Neha", "Pooja", "Sneha", "Kavya", "Divya", "Shreya", "Nisha",
    "Manav", "Advait",
]
LAST_NAMES = [
    "Sharma", "Verma", "Gupta", "Mehta", "Iyer", "Nair", "Reddy", "Rao",
    "Patel", "Shah", "Kulkarni", "Joshi", "Malhotra", "Chopra", "Kapoor",
    "Bansal", "Agarwal", "Mishra", "Pandey", "Singh", "Yadav", "Chauhan",
    "Bhatt", "Desai", "Menon",
]
DEPARTMENTS = ["CSE", "ECE", "ME", "IT", "Civil", "EEE"]
BATCH_YEARS = ["2023", "2024", "2025"]

SUBJECTS_BY_DEPT = {
    "CSE": ["Data Structures", "Operating Systems", "DBMS", "Computer Networks", "OOPs with Java", "Web Development"],
    "IT": ["Data Structures", "Cloud Computing", "DBMS", "Computer Networks", "Software Engineering", "Web Development"],
    "ECE": ["Digital Electronics", "Signals & Systems", "Microprocessors", "Communication Systems", "Control Systems"],
    "EEE": ["Circuit Theory", "Electrical Machines", "Power Systems", "Control Systems", "Digital Electronics"],
    "ME": ["Thermodynamics", "Fluid Mechanics", "Machine Design", "Manufacturing Processes", "Engineering Mechanics"],
    "Civil": ["Structural Analysis", "Surveying", "Geotechnical Engineering", "Concrete Technology", "Fluid Mechanics"],
}

FOCUS_PRESETS = [
    ("DSA Prep", 6), ("Placement Prep", 7), ("Personal Project", 5),
    ("Certification Course", 4), ("Hackathon Prep", 8), ("Research Paper Work", 6),
]
RECOVERY_PRESETS = [
    ("Extra Sleep", 5), ("Sports", 6), ("Meditation", 4),
    ("Music/Hobby", 3), ("Outing with Friends", 4), ("Gaming Break", 3),
]

MOODS = ["😄 Good", "😐 Neutral", "😫 Stressed"]

# Each student gets a randomly-assigned "profile" so the month of history isn't
# flat -- this is what makes the Insights trend/forecast chart show DECLINING,
# IMPROVING and STABLE students, and makes the faculty heatmap look real.
PROFILES = ["declining", "improving", "stable", "stable", "at_risk"]  # weighted

WELLNESS_LOG_XP = 20  # matches the backend's reward per check-in


def rand_username(first, last, used):
    base = f"{first.lower()}.{last.lower()}"
    candidate = base
    n = 1
    while candidate in used:
        candidate = f"{base}{n}"
        n += 1
    used.add(candidate)
    return candidate


def api_post(path, json_body=None, token=None, params=None, retries=3):
    url = f"{BASE_URL}{path}"
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    for attempt in range(retries):
        try:
            return requests.post(url, json=json_body, params=params, headers=headers, timeout=90)
        except requests.exceptions.RequestException as e:
            print(f"    ! request failed ({e}), retrying ({attempt+1}/{retries})...")
            time.sleep(3)
    raise RuntimeError(f"POST {path} failed after {retries} retries")


def login(username, password):
    """Returns a login token for the account, or None if the credentials are wrong."""
    r = api_post("/api/login", {"username": username, "password": password})
    return r.json()["token"] if r.status_code == 200 else None


def wait_for_backend():
    print(f"Checking the backend at {BASE_URL} (a sleeping Render server can take 30-60s to wake)...")
    for _ in range(10):
        try:
            if requests.get(f"{BASE_URL}/api/admin-exists", timeout=90).status_code == 200:
                print("Backend is up.\n")
                return True
        except requests.exceptions.RequestException:
            pass
        time.sleep(5)
    return False


def build_mongo_uri():
    """Takes the raw MONGODB_URI (password NOT URL-encoded, exactly as MongoDB
    Atlas shows it) and rebuilds it with the username/password safely
    URL-encoded, so special characters like @ # % ! in the password don't break
    the connection string."""
    if not MONGODB_URI or "PASTE_NEW_PASSWORD_HERE" in MONGODB_URI:
        return None

    scheme = "mongodb+srv://" if MONGODB_URI.startswith("mongodb+srv://") else "mongodb://"
    remainder = MONGODB_URI[len(scheme):]

    # Split off "/dbname?params" (everything after the first "/")
    if "/" in remainder:
        authority, rest = remainder.split("/", 1)
    else:
        authority, rest = remainder, ""

    # authority looks like "user:pass@host" -- split on the LAST "@" since the
    # password (before it) might itself legitimately contain "@", but the host
    # (after it) never will.
    creds, host = authority.rsplit("@", 1)
    user, pwd = creds.split(":", 1)  # first ":" splits user from password

    # Force the database name regardless of what was (or wasn't) already in the
    # pasted string, and keep query params (retryWrites etc.) if present.
    params = rest.split("?", 1)[1] if "?" in rest else "retryWrites=true&w=majority"
    return f"{scheme}{quote_plus(user)}:{quote_plus(pwd)}@{host}/{DB_NAME}?{params}"


# ============================================================================
# Faculty accounts (optional)
# ============================================================================
def create_faculty(n, used):
    print(f"Creating {n} faculty accounts...")
    # Only an admin may create faculty accounts, so log in as the admin first
    admin_user = os.environ.get("SEED_ADMIN_USERNAME", "")
    admin_token = login(admin_user, os.environ.get("SEED_ADMIN_PASSWORD", "")) if admin_user else None
    if admin_token is None:
        print("  ! SEED_ADMIN_USERNAME / SEED_ADMIN_PASSWORD in .env are missing or wrong -- skipping faculty.")
        return

    for i in range(n):
        first, last = random.choice(FIRST_NAMES), random.choice(LAST_NAMES)
        username = rand_username(first, last, used)
        dept = DEPARTMENTS[i % len(DEPARTMENTS)]
        body = {"name": f"{first} {last}", "username": username, "password": DEFAULT_PASSWORD, "department": dept}
        r = api_post("/api/admin/create-faculty", body, token=admin_token)
        if r.status_code == 200:
            print(f"  + faculty {username} ({dept})")
        else:
            print(f"  ! skipped {username}: {r.text}")


# ============================================================================
# Student accounts + semester + subjects + activities + assignments (via the API)
# ============================================================================
def create_student(username, first, last, dept, sem_start, sem_end, today):
    """Creates one fully set-up student through the API. Returns True on success."""
    r = api_post("/api/register", {
        "name": f"{first} {last}",
        "username": username,
        "password": DEFAULT_PASSWORD,
        "department": dept,
        "batchYear": random.choice(BATCH_YEARS),
    })
    if r.status_code != 200:
        print(f"  ! register failed for {username}: {r.text}")
        return False

    # Every per-student call needs that student's own login token
    token = login(username, DEFAULT_PASSWORD)
    if token is None:
        print(f"  ! login failed for freshly registered {username}")
        return False

    r = api_post(f"/api/semesters/{username}", {"startDate": sem_start, "endDate": sem_end}, token=token)
    if r.status_code != 200:
        print(f"  ! semester creation failed for {username}: {r.text}")
        return False

    # ---- Subjects (up to 4) + both calibration weeks for each ----
    dept_subjects = SUBJECTS_BY_DEPT.get(dept, SUBJECTS_BY_DEPT["CSE"])
    chosen_subjects = random.sample(dept_subjects, k=min(4, len(dept_subjects)))
    for subj in chosen_subjects:
        api_post(f"/api/subjects/{username}", {
            "subjectName": subj, "department": dept, "credits": random.choice([3, 4]),
        }, token=token)
        for week in (1, 2):
            api_post(f"/api/calibration/{username}", {
                "subjectName": subj,
                "difficulty": random.randint(2, 5),
                "studyHours": round(random.uniform(3, 10), 1),
                "weekNumber": week,
            }, token=token)

    # ---- Focus activities (1-3) and recovery activities (1-2) ----
    for name, weight in random.sample(FOCUS_PRESETS, k=random.randint(1, 3)):
        api_post(f"/api/focus-activity/{username}", {"name": name, "weight": weight, "custom": False}, token=token)
    for name, weight in random.sample(RECOVERY_PRESETS, k=random.randint(1, 2)):
        api_post(f"/api/recovery-activity/{username}", {"name": name, "weight": weight, "custom": False}, token=token)

    # ---- Assignments (5-8), about half submitted ----
    for a in range(random.randint(5, 8)):
        subj = random.choice(chosen_subjects)
        r = api_post(f"/api/assignments/{username}", {
            "title": f"{subj} Assignment {a+1}",
            "subject": subj,
            "difficulty": random.choice(["Easy", "Medium", "Hard"]),
            "priority": random.randint(1, 3),
            "deadline": (today + timedelta(days=random.randint(-10, 20))).isoformat(),
        }, token=token)
        if r.status_code == 200 and random.random() < 0.5:
            api_post(f"/api/submit-task/{username}", params={"id": r.json()["id"]}, token=token)

    return True


def create_students(n, used):
    if n <= 0:
        return
    print(f"Creating {n} student accounts through the API...")
    today = date.today()
    sem_start = (today - timedelta(days=35)).isoformat()
    sem_end = (today + timedelta(days=55)).isoformat()

    created, attempts = 0, 0
    while created < n and attempts < n * 3:
        attempts += 1
        first, last = random.choice(FIRST_NAMES), random.choice(LAST_NAMES)
        username = rand_username(first, last, used)
        dept = random.choice(DEPARTMENTS)
        if create_student(username, first, last, dept, sem_start, sem_end, today):
            created += 1
            print(f"  + [{created}/{n}] student {username} ({dept})")
    if created < n:
        print(f"  ! only created {created} of {n} students -- re-run the script to finish.")


# ============================================================================
# Backdated Wellness Logs + Balance Score history (direct MongoDB writes)
# ============================================================================
def profile_day_values(profile, day_index, total_days):
    """Returns (sleepHours, studyHours, mood, energyLevel, physicalActivity,
    balanceScore, workloadPercent, burnoutRisk) for a given day, shaped by profile."""
    t = day_index / max(total_days - 1, 1)  # 0 -> 1 across the month
    noise = lambda spread: random.uniform(-spread, spread)

    if profile == "declining":
        balance = max(15, 85 - t * 55 + noise(6))
        sleep = max(3.5, 7.5 - t * 3 + noise(0.6))
        study = min(11, 4 + t * 5 + noise(1))
        physical = random.random() < 0.25
    elif profile == "improving":
        balance = min(95, 45 + t * 45 + noise(6))
        sleep = min(9, 5.5 + t * 2.5 + noise(0.6))
        study = max(2, 7 - t * 3 + noise(1))
        physical = random.random() < 0.55
    elif profile == "at_risk":
        balance = max(10, 40 - t * 15 + noise(8))
        sleep = max(3, 4.5 + noise(0.7))
        study = min(12, 8 + noise(1.5))
        physical = random.random() < 0.15
    else:  # stable
        balance = min(88, max(55, 72 + noise(8)))
        sleep = min(8.5, max(5.5, 7 + noise(0.8)))
        study = min(8, max(3, 5.5 + noise(1.2)))
        physical = random.random() < 0.4

    if balance >= 70:
        mood = MOODS[0]
    elif balance >= 40:
        mood = MOODS[1]
    else:
        mood = MOODS[2]

    energy = 5 if balance > 80 else 4 if balance > 60 else 3 if balance > 40 else 2 if balance > 20 else 1
    workload_percent = round(max(5, min(140, 100 - balance + noise(10))), 1)
    risk = "LOW" if balance >= 70 else "MEDIUM" if balance >= 40 else "HIGH"
    workload_index = round(workload_percent * 0.6, 1)  # rough back-calc from the 60hr baseline

    return {
        "sleepHours": round(sleep, 1),
        "studyHours": round(study, 1),
        "mood": mood,
        "energyLevel": energy,
        "physicalActivity": physical,
        "balanceScore": round(balance, 1),
        "workloadPercent": workload_percent,
        "workloadIndex": workload_index,
        "burnoutRisk": risk,
    }


def seed_missing_history(db, days=HISTORY_DAYS):
    students_col = db["students"]
    analytics_col = db["analytics_results"]
    have_history = set(analytics_col.distinct("username"))
    missing = [s for s in students_col.find({"role": "STUDENT"}) if s["username"] not in have_history]

    print(f"\n{len(missing)} students have no Balance Score history -- adding {days} days for each...")
    today = date.today()

    for s in missing:
        username = s["username"]
        profile = random.choice(PROFILES)
        semesters = s.get("semesters", [])
        active = next((sem for sem in semesters if sem.get("active")), None)
        sem_number = active["number"] if active else max((sem.get("number", 1) for sem in semesters), default=1)

        analytics_docs, wellness_logs = [], []
        existing_log_dates = {l.get("date") for l in (active or {}).get("wellnessLogs", [])}
        for i in range(days):
            day = (today - timedelta(days=days - 1 - i)).isoformat()
            vals = profile_day_values(profile, i, days)
            analytics_docs.append({
                "username": username,
                "department": s.get("department"),
                "currentSemester": sem_number,
                "date": day,
                "workloadIndex": vals["workloadIndex"],
                "workloadPercent": vals["workloadPercent"],
                "balanceScore": vals["balanceScore"],
                "burnoutRisk": vals["burnoutRisk"],
            })
            if day not in existing_log_dates:
                wellness_logs.append({
                    "date": day,
                    "sleepHours": vals["sleepHours"],
                    "studyHours": vals["studyHours"],
                    "mood": vals["mood"],
                    "energyLevel": vals["energyLevel"],
                    "physicalActivity": vals["physicalActivity"],
                })

        analytics_col.insert_many(analytics_docs)

        # The matching check-ins go into the active semester, with the streak and XP they'd have earned
        if active and wellness_logs:
            students_col.update_one(
                {"_id": s["_id"]},
                {
                    "$push": {"semesters.$[sem].wellnessLogs": {"$each": wellness_logs}},
                    "$set": {"dailyStreak": days, "lastCheckInDate": today.isoformat()},
                    "$inc": {"totalXP": WELLNESS_LOG_XP * len(wellness_logs)},
                },
                array_filters=[{"sem.number": sem_number}],
            )

        print(f"  + {username}: {days} days of history ({profile})")


# ============================================================================
# Main
# ============================================================================
if __name__ == "__main__":
    print("=" * 70)
    print("CampusBalance demo data seeder")
    print("=" * 70)

    uri = build_mongo_uri()
    if uri is None:
        print("MONGODB_URI is not set -- put it in the .env file (see .env.example).")
        sys.exit(1)
    if not wait_for_backend():
        print(f"Could not reach the backend at {BASE_URL}. Start it first, then re-run.")
        sys.exit(1)

    client = MongoClient(uri)
    db = client[DB_NAME]
    used_usernames = set(db["students"].distinct("username"))

    if NUM_FACULTY_TO_ADD > 0:
        create_faculty(NUM_FACULTY_TO_ADD, used_usernames)

    existing = db["students"].count_documents({"role": "STUDENT"})
    print(f"Students right now: {existing} (target: {TARGET_TOTAL_STUDENTS})")
    create_students(TARGET_TOTAL_STUDENTS - existing, used_usernames)

    seed_missing_history(db)

    total = db["students"].count_documents({"role": "STUDENT"})
    with_history = len(set(db["analytics_results"].distinct("username")) &
                       set(db["students"].distinct("username", {"role": "STUDENT"})))
    client.close()

    print("\nDone!")
    print(f"Students: {total}  |  with Balance Score history: {with_history}")
    print(f"Every seeded account's password is: {DEFAULT_PASSWORD}")
