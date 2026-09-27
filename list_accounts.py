"""
CampusBalance account lister
==============================
Pulls every seeded student + faculty account straight from MongoDB and saves
a clean CSV (username, role, name, department) -- much easier to reference
than scrolling through your terminal history.

All seeded accounts (from seed_data.py) share the password: Campus@123
Your admin account is separate and keeps its own password.

SETUP: pip install pymongo   (you already have this from seed_data.py)
RUN:   python list_accounts.py
"""

import csv
from urllib.parse import quote_plus

from pymongo import MongoClient

# Paste the SAME MongoDB connection string you used in seed_data.py
MONGODB_URI = "mongodb+srv://guptamanan645_db_user:9311418110@manancluster1.4qhtora.mongodb.net/?appName=MananCluster1"
DB_NAME = "campusbalance"
OUTPUT_FILE = "campusbalance_accounts.csv"


def build_mongo_uri():
    scheme = "mongodb+srv://" if MONGODB_URI.startswith("mongodb+srv://") else "mongodb://"
    remainder = MONGODB_URI[len(scheme):]
    authority, rest = remainder.split("/", 1) if "/" in remainder else (remainder, "")
    creds, host = authority.rsplit("@", 1)
    user, pwd = creds.split(":", 1)
    params = rest.split("?", 1)[1] if "?" in rest else "retryWrites=true&w=majority"
    return f"{scheme}{quote_plus(user)}:{quote_plus(pwd)}@{host}/{DB_NAME}?{params}"


if __name__ == "__main__":
    if "PASTE_YOUR" in MONGODB_URI:
        print("Edit MONGODB_URI at the top of this script first (same one from seed_data.py).")
        raise SystemExit(1)

    client = MongoClient(build_mongo_uri())
    db = client[DB_NAME]

    students = list(db["students"].find(
        {"role": "STUDENT"},
        {"username": 1, "name": 1, "department": 1, "_id": 0}
    ).sort("username", 1))

    faculty = list(db["students"].find(
        {"role": "FACULTY"},
        {"username": 1, "name": 1, "department": 1, "_id": 0}
    ).sort("username", 1))

    with open(OUTPUT_FILE, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["username", "role", "name", "department", "password"])
        for s in students:
            writer.writerow([s.get("username", ""), "STUDENT", s.get("name", ""), s.get("department", ""), "Campus@123"])
        for fct in faculty:
            writer.writerow([fct.get("username", ""), "FACULTY", fct.get("name", ""), fct.get("department", ""), "Campus@123"])

    client.close()

    print(f"Found {len(students)} students and {len(faculty)} faculty accounts.")
    print(f"Saved to {OUTPUT_FILE} -- open it in Excel to see the full list.")
    print("Every account in this list uses the password: Campus@123")