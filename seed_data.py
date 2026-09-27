"""
CampusBalance demo data seeder
================================
Creates realistic-looking faculty + student accounts (each unique), gives every
student a running semester with subjects, assignments, focus/recovery activities,
and completed 2-week calibration logs -- all done the "honest" way, through your
real backend API (so passwords are hashed properly, IDs are generated properly,
and every business rule your app already enforces still applies).

Then, because your backend deliberately refuses to backdate a Wellness Log or a
daily Balance Score snapshot (it always stamps "today" -- that's a real anti-cheat
rule in StudentService/AnalyticsService, not a bug), this script writes 30 days of
believable WellnessLog + analytics_results history *directly into MongoDB* for
each student, so your Insights trend charts, faculty heatmap, and admin reports
all have a full month of data to show instead of a single flat point.

IMPORTANT - this is DEMO/TEST data for showing your teacher a working, populated
app. It is not real student data and should never be described as real results
in your research paper -- keep that fabricated/synthetic distinction clear.

---------------------------------------------------------------------------
SETUP (one-time):
    pip install requests pymongo

FILL IN BELOW before running:
    BASE_URL      -> your live Render backend URL (already filled in)
    MONGODB_URI   -> YOUR real MongoDB Atlas connection string (the same one
                     you put in Render's environment variables). Get it from
                     MongoDB Atlas -> Connect -> Drivers -> Java, then swap
                     <password> for your real password. Paste it below.
                     This never leaves your machine -- the script runs locally.

RUN:
    python seed_data.py
---------------------------------------------------------------------------
"""

import json
import os
import random
import string
import sys
import time
from datetime import date, timedelta
from urllib.parse import quote_plus

import requests
from pymongo import MongoClient

# ============================================================================
# CONFIG -- edit these lines
# ============================================================================
BASE_URL = "https://campusbalance-backend.onrender.com"   # your live Render URL

# Paste your full MongoDB Atlas connection string here -- the same one you put
# in Render's environment variables (MONGODB_URI). Get it from MongoDB Atlas ->
# Connect -> Drivers -> Java, then swap <password> for your real password.
# You do NOT need to URL-encode special characters yourself (@ # % ! etc. in
# your password are fine as-is) -- the script re-encodes it safely before use.
MONGODB_URI = "mongodb+srv://guptamanan645_db_user:9311418110@manancluster1.4qhtora.mongodb.net/?appName=MananCluster1"

STUDENTS_CACHE_FILE = "seeded_students.json"   # remembers who was created, so a
                                                # failed history backfill can be
                                                # retried without recreating accounts

NUM_STUDENTS = 50
NUM_FACULTY = 8
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


def rand_username(first, last, used):
    base = f"{first.lower()}.{last.lower()}"
    candidate = base
    n = 1
    while candidate in used:
        candidate = f"{base}{n}"
        n += 1
    used.add(candidate)
    return candidate


def api_post(path, json_body, retries=3):
    url = f"{BASE_URL}{path}"
    for attempt in range(retries):
        try:
            r = requests.post(url, json=json_body, timeout=90)
            return r
        except requests.exceptions.RequestException as e:
            print(f"    ! request failed ({e}), retrying ({attempt+1}/{retries})...")
            time.sleep(3)
    raise RuntimeError(f"POST {path} failed after {retries} retries")


def warm_up_backend():
    print("Waking up your Render backend (free tier cold start can take 30-60s)...")
    for _ in range(10):
        try:
            r = requests.get(f"{BASE_URL}/api/admin-exists", timeout=90)
            if r.status_code == 200:
                print("Backend is awake.\n")
                return
        except requests.exceptions.RequestException:
            pass
        time.sleep(5)
    print("Backend didn't respond in time -- continuing anyway, first requests may be slow.\n")


# ============================================================================
# Step 1: Faculty accounts
# ============================================================================
def create_faculty(n):
    print(f"Creating {n} faculty accounts...")
    used = set()
    created = []
    for i in range(n):
        first = random.choice(FIRST_NAMES)
        last = random.choice(LAST_NAMES)
        username = rand_username(first, last, used)
        dept = DEPARTMENTS[i % len(DEPARTMENTS)]
        body = {
            "name": f"{first} {last}",
            "username": username,
            "password": DEFAULT_PASSWORD,
            "department": dept,
        }
        r = api_post("/api/admin/create-faculty", body)
        if r.status_code == 200:
            created.append(body)
            print(f"  + faculty {username} ({dept})")
        else:
            print(f"  ! skipped {username}: {r.text}")
    return created


# ============================================================================
# Step 2: Student accounts + semester + subjects + activities + assignments
# ============================================================================
def create_students(n):
    print(f"\nCreating {n} student accounts...")
    used = set()
    students = []

    today = date.today()
    sem_start = (today - timedelta(days=35)).isoformat()
    sem_end = (today + timedelta(days=55)).isoformat()

    for i in range(n):
        first = random.choice(FIRST_NAMES)
        last = random.choice(LAST_NAMES)
        username = rand_username(first, last, used)
        dept = random.choice(DEPARTMENTS)
        profile = random.choice(PROFILES)

        reg_body = {
            "name": f"{first} {last}",
            "username": username,
            "password": DEFAULT_PASSWORD,
            "department": dept,
            "batchYear": random.choice(BATCH_YEARS),
        }
        r = api_post("/api/register", reg_body)
        if r.status_code != 200:
            print(f"  ! register failed for {username}: {r.text}")
            continue

        # ---- Semester ----
        sem_body = {"startDate": sem_start, "endDate": sem_end}
        r = api_post(f"/api/semesters/{username}", sem_body)
        if r.status_code != 200:
            print(f"  ! semester creation failed for {username}: {r.text}")
            continue

        # ---- Subjects (3-4 per student) + calibration (2 weeks each, real API calls) ----
        dept_subjects = SUBJECTS_BY_DEPT.get(dept, SUBJECTS_BY_DEPT["CSE"])
        chosen_subjects = random.sample(dept_subjects, k=min(4, len(dept_subjects)))
        for subj in chosen_subjects:
            api_post(f"/api/subjects/{username}", {
                "subjectName": subj, "department": dept, "credits": random.choice([3, 4]),
            })
            for week in (1, 2):
                api_post(f"/api/calibration/{username}", {
                    "subjectName": subj,
                    "difficulty": random.randint(2, 5),
                    "studyHours": round(random.uniform(3, 10), 1),
                    "weekNumber": week,
                })

        # ---- Focus activities (1-3) ----
        for name, weight in random.sample(FOCUS_PRESETS, k=random.randint(1, 3)):
            api_post(f"/api/focus-activity/{username}", {
                "name": name, "weight": weight, "custom": False,
            })

        # ---- Recovery activities (1-2) ----
        for name, weight in random.sample(RECOVERY_PRESETS, k=random.randint(1, 2)):
            api_post(f"/api/recovery-activity/{username}", {
                "name": name, "weight": weight, "custom": False,
            })

        # ---- Assignments (5-8, mix of pending/completed) ----
        num_assignments = random.randint(5, 8)
        for a in range(num_assignments):
            subj = random.choice(chosen_subjects)
            difficulty = random.choice(["Easy", "Medium", "Hard"])
            deadline = (today + timedelta(days=random.randint(-10, 20))).isoformat()
            title = f"{subj} Assignment {a+1}"
            api_post(f"/api/assignments/{username}", {
                "title": title, "subject": subj, "difficulty": difficulty,
                "priority": random.randint(1, 3), "deadline": deadline,
            })
            if random.random() < 0.5:  # ~half get marked completed
                requests.post(
                    f"{BASE_URL}/api/submit-task/{username}",
                    params={"title": title}, timeout=90,
                )

        students.append({"username": username, "department": dept, "profile": profile})
        print(f"  + student {username} ({dept}, profile={profile})")

    # Save the list so a failed/interrupted history backfill can be retried
    # without recreating accounts (which would just 409 anyway, but wastes time).
    with open(STUDENTS_CACHE_FILE, "w") as f:
        json.dump(students, f, indent=2)
    print(f"\nSaved student list to {STUDENTS_CACHE_FILE} (used to resume the history step if needed).")

    return students


# ============================================================================
# Step 3: Backdated Wellness Logs + Analytics history (direct MongoDB writes)
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


def build_mongo_uri():
    """Takes the raw MONGODB_URI you pasted (password NOT URL-encoded, exactly
    as MongoDB Atlas shows it to you) and rebuilds it with the username/password
    safely URL-encoded, so special characters like @ # % ! in your password
    don't break the connection string."""
    if "PASTE_YOUR" in MONGODB_URI:
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

    user_enc = quote_plus(user)
    pwd_enc = quote_plus(pwd)

    # Force the database name to campusbalance regardless of what was (or wasn't)
    # already in the pasted string, and keep query params (retryWrites etc.) if present.
    if "?" in rest:
        _, params = rest.split("?", 1)
    else:
        params = "retryWrites=true&w=majority"

    return f"{scheme}{user_enc}:{pwd_enc}@{host}/{DB_NAME}?{params}"


def seed_history(students, days=30):
    print(f"\nConnecting directly to MongoDB to backfill {days} days of wellness + analytics history...")
    uri = build_mongo_uri()
    if uri is None:
        print("  ! MONGODB_URI is still a placeholder -- skipping history backfill.")
        print("    Edit MONGODB_URI at the top of this script and re-run to add the month of history.")
        return

    client = MongoClient(uri)
    db = client[DB_NAME]
    students_col = db["students"]
    analytics_col = db["analytics_results"]

    today = date.today()

    for s in students:
        username = s["username"]
        profile = s["profile"]
        dept = s["department"]

        wellness_logs = []
        analytics_docs = []

        for i in range(days):
            day = today - timedelta(days=(days - 1 - i))
            vals = profile_day_values(profile, i, days)

            wellness_logs.append({
                "date": day.isoformat(),
                "sleepHours": vals["sleepHours"],
                "studyHours": vals["studyHours"],
                "mood": vals["mood"],
                "energyLevel": vals["energyLevel"],
                "physicalActivity": vals["physicalActivity"],
            })

            analytics_docs.append({
                "username": username,
                "department": dept,
                "currentSemester": 1,
                "date": day.isoformat(),
                "workloadIndex": vals["workloadIndex"],
                "workloadPercent": vals["workloadPercent"],
                "balanceScore": vals["balanceScore"],
                "burnoutRisk": vals["burnoutRisk"],
            })

        # Push all 30 wellness logs into the student's active semester in one update.
        # $push + $each appends without disturbing today's real log if one already exists.
        students_col.update_one(
            {"username": username, "semesters.active": True},
            {"$push": {"semesters.$[elem].wellnessLogs": {"$each": wellness_logs}}},
            array_filters=[{"elem.active": True}],
        )

        # Remove any placeholder "today" snapshot so it doesn't duplicate, then insert the month.
        analytics_col.delete_many({"username": username})
        analytics_col.insert_many(analytics_docs)

        print(f"  + {username}: {days} days of history seeded ({profile})")

    client.close()
    print("\nHistory backfill complete.")


# ============================================================================
# Main
# ============================================================================
if __name__ == "__main__":
    print("=" * 70)
    print("CampusBalance demo data seeder")
    print("=" * 70)

    if os.path.exists(STUDENTS_CACHE_FILE):
        with open(STUDENTS_CACHE_FILE) as f:
            students = json.load(f)
        print(f"Found {STUDENTS_CACHE_FILE} with {len(students)} students already created --")
        print("skipping account creation and going straight to the history backfill.")
        print(f"(Delete {STUDENTS_CACHE_FILE} first if you actually want to create a fresh batch.)\n")
    else:
        if "onrender.com" in BASE_URL:
            warm_up_backend()
        create_faculty(NUM_FACULTY)
        students = create_students(NUM_STUDENTS)

    seed_history(students, days=30)

    print("\nDone!")
    print(f"Seeded {len(students)} students and faculty accounts.")
    print(f"Every seeded account's password is: {DEFAULT_PASSWORD}")
    print("Log in as any seeded username on your live site to see populated data,")
    print("or check the Admin dashboard / Faculty heatmap for the aggregate view.")