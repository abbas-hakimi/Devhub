import getpass
import sys
from datetime import datetime
import requests

BASE_URL = "http://localhost:8080"
TIMEOUT = 10
s = requests.Session()
passed = failed = 0

def test(name, ok, r=None):
    global passed, failed
    if ok:
        passed += 1
        print(f"[PASS] {name}")
    else:
        failed += 1
        print(f"[FAIL] {name}")
        if r is not None:
            print(f"       HTTP {r.status_code}: {r.text[:600]}")

def j(r):
    try:
        return r.json()
    except Exception:
        return {}

def req(method, path, token=None, **kwargs):
    h = kwargs.pop("headers", {})
    if token:
        h = {"Authorization": f"Bearer {token}", "Content-Type": "application/json", **h}
    try:
        return s.request(method, BASE_URL + path, headers=h, timeout=TIMEOUT, **kwargs)
    except requests.RequestException as e:
        print(f"[REQUEST ERROR] {method} {path}: {e}")
        return None

def login(identifier, password, name):
    r = req("POST", "/api/auth/login",
            json={"identifier": identifier, "password": password})
    d = j(r) if r else {}
    token = d.get("accessToken")
    test(name, r is not None and r.status_code == 200 and bool(token), r)
    return token

def create_repo(token, name, visibility):
    r = req("POST", "/api/repositories", token=token, json={
        "name": name, "description": "Phase 6 automated test", "visibility": visibility
    })
    d = j(r) if r else {}
    rid = d.get("id")
    test(f"Create {visibility.lower()} repository",
         r is not None and r.status_code == 201 and bool(rid), r)
    return rid

def create_milestone(token, rid, title, description=None, due=None, name=None):
    body = {"title": title}
    if description is not None: body["description"] = description
    if due is not None: body["dueDate"] = due
    r = req("POST", f"/api/repositories/{rid}/milestones", token=token, json=body)
    d = j(r) if r else {}
    mid = d.get("id")
    test(name or "Create milestone",
         r is not None and r.status_code == 201 and bool(mid), r)
    return mid

print("=" * 65)
print("PHASE 6 — MILESTONE API TEST SUITE")
print("=" * 65)
print(f"\nBackend: {BASE_URL}\n")

pa = getpass.getpass("Password for postmanA: ")
pb = getpass.getpass("Password for postmanB: ")

print("\n" + "-" * 65)
print("AUTHENTICATION")
print("-" * 65)
a = login("postmanA", pa, "User A login")
b = login("postmanB", pb, "User B login")
if not a or not b:
    print("Cannot continue without both JWT tokens.")
    sys.exit(1)

r = req("GET", "/api/auth/me", token=a); test("User A /me", r is not None and r.status_code == 200, r)
r = req("GET", "/api/auth/me", token=b); test("User B /me", r is not None and r.status_code == 200, r)

run = datetime.now().strftime("%Y%m%d%H%M%S%f")
pub = priv = repo2 = None
m1 = m2 = cross = issue = None

print("\n" + "-" * 65)
print("REPOSITORY SETUP")
print("-" * 65)
pub = create_repo(a, f"phase6-auto-public-{run}", "PUBLIC")
priv = create_repo(a, f"phase6-auto-private-{run}", "PRIVATE")
repo2 = create_repo(a, f"phase6-auto-second-{run}", "PUBLIC")
if not all((pub, priv, repo2)):
    print("Repository setup failed; stopping.")
    sys.exit(1)
print(f"\nPublic repository ID : {pub}")
print(f"Private repository ID: {priv}")
print(f"Second repository ID : {repo2}")

print("\n" + "-" * 65)
print("MILESTONE CRUD")
print("-" * 65)
m1 = create_milestone(a, pub, "  v1.0 Release  ", "  First stable release  ",
                      "2026-12-31", "Owner creates milestone")
r = req("GET", f"/api/repositories/{pub}/milestones")
test("List milestones", r is not None and r.status_code == 200, r)
r = req("GET", f"/api/milestones/{m1}")
d = j(r) if r else {}
test("Get milestone", r is not None and r.status_code == 200, r)
test("Title trimmed", d.get("title") == "v1.0 Release", r)
test("Description trimmed", d.get("description") == "First stable release", r)
test("Due date persisted", d.get("dueDate") == "2026-12-31", r)
test("Default state is OPEN", d.get("state") == "OPEN", r)
test("closedAt initially null", d.get("closedAt") is None, r)
test("Repository is correct", str(d.get("repository", {}).get("id")) == str(pub), r)
test("createdAt exists", bool(d.get("createdAt")), r)
test("updatedAt exists", bool(d.get("updatedAt")), r)

print("\n" + "-" * 65)
print("UPDATE / LIFECYCLE")
print("-" * 65)
r = req("PUT", f"/api/milestones/{m1}", token=a,
        json={"title": "v1.0 Final Release", "description": "Updated",
              "dueDate": "2027-01-15"})
d = j(r) if r else {}
test("Owner updates milestone", r is not None and r.status_code == 200, r)
test("Updated title persisted", d.get("title") == "v1.0 Final Release", r)
test("Updated description persisted", d.get("description") == "Updated", r)
test("Updated due date persisted", d.get("dueDate") == "2027-01-15", r)

r = req("PUT", f"/api/milestones/{m1}", token=a, json={"state": "CLOSED"})
d = j(r) if r else {}
test("Owner closes milestone", r is not None and r.status_code == 200, r)
test("Closed state persisted", d.get("state") == "CLOSED", r)
test("Closing sets closedAt", bool(d.get("closedAt")), r)

r = req("PUT", f"/api/milestones/{m1}", token=a, json={"state": "OPEN"})
d = j(r) if r else {}
test("Owner reopens milestone", r is not None and r.status_code == 200, r)
test("Reopened state persisted", d.get("state") == "OPEN", r)
test("Reopening clears closedAt", d.get("closedAt") is None, r)

print("\n" + "-" * 65)
print("VALIDATION / DUPLICATES")
print("-" * 65)
r = req("POST", f"/api/repositories/{pub}/milestones", token=a,
        json={"title": "v1.0 Final Release"})
test("Duplicate title rejected", r is not None and r.status_code == 409, r)

r = req("POST", f"/api/repositories/{pub}/milestones", token=a,
        json={"title": "  V1.0 FINAL RELEASE  "})
test("Case/whitespace duplicate rejected", r is not None and r.status_code == 409, r)

same = create_milestone(a, repo2, "v1.0 Final Release",
                        name="Same title allowed in another repository")
m2 = create_milestone(a, pub, "v1.1 Release", "Second milestone",
                      "2027-06-30", "Create second milestone")

r = req("POST", f"/api/repositories/{pub}/milestones", token=a, json={"title": ""})
test("Blank title rejected", r is not None and r.status_code == 400, r)
r = req("POST", f"/api/repositories/{pub}/milestones", token=a,
        json={"title": "x" * 101})
test("Oversized title rejected", r is not None and r.status_code == 400, r)
r = req("POST", f"/api/repositories/{pub}/milestones", token=a,
        json={"title": "Long description", "description": "x" * 16001})
test("Oversized description rejected", r is not None and r.status_code == 400, r)
r = req("POST", f"/api/repositories/{pub}/milestones", token=a,
        json={"title": "Bad date", "dueDate": "not-a-date"})
test("Malformed dueDate rejected", r is not None and r.status_code == 400, r)
r = req("PUT", f"/api/milestones/{m1}", token=a, json={"state": "INVALID"})
test("Invalid state rejected", r is not None and r.status_code == 400, r)

print("\n" + "-" * 65)
print("PUBLIC / PRIVATE VISIBILITY")
print("-" * 65)
r = req("GET", f"/api/repositories/{pub}/milestones")
test("Anonymous can list public milestones", r is not None and r.status_code == 200, r)
r = req("GET", f"/api/milestones/{m1}")
test("Anonymous can get public milestone", r is not None and r.status_code == 200, r)
r = req("GET", f"/api/repositories/{pub}/milestones", token=b)
test("User B can list public milestones", r is not None and r.status_code == 200, r)
r = req("GET", f"/api/repositories/{priv}/milestones", token=b)
test("Non-owner cannot list private milestones", r is not None and r.status_code == 404, r)
r = req("GET", f"/api/repositories/{priv}/milestones")
test("Anonymous cannot list private milestones", r is not None and r.status_code in (401, 404), r)

print("\n" + "-" * 65)
print("WRITE AUTHORIZATION")
print("-" * 65)
r = req("POST", f"/api/repositories/{pub}/milestones", token=b,
        json={"title": "Unauthorized"})
test("Non-owner cannot create milestone", r is not None and r.status_code == 403, r)
r = req("PUT", f"/api/milestones/{m1}", token=b, json={"title": "Unauthorized"})
test("Non-owner cannot update milestone", r is not None and r.status_code == 403, r)
r = req("DELETE", f"/api/milestones/{m1}", token=b)
test("Non-owner cannot delete milestone", r is not None and r.status_code == 403, r)

print("\n" + "-" * 65)
print("ISSUE / MILESTONE INTEGRATION")
print("-" * 65)
r = req("POST", f"/api/repositories/{pub}/issues", token=b,
        json={"title": "Phase 6 integration issue",
              "description": "Automated test"})
d = j(r) if r else {}
issue = d.get("id")
test("User B creates issue", r is not None and r.status_code == 201 and bool(issue), r)

r = req("PUT", f"/api/issues/{issue}", token=a, json={"milestoneId": m1})
d = j(r) if r else {}
test("Owner assigns milestone", r is not None and r.status_code == 200, r)
test("Issue response contains milestone", str(d.get("milestone", {}).get("id")) == str(m1), r)

r = req("GET", f"/api/issues/{issue}", token=b)
d = j(r) if r else {}
test("Milestone assignment persists", str(d.get("milestone", {}).get("id")) == str(m1), r)

r = req("PUT", f"/api/issues/{issue}", token=a, json={"milestoneId": m2})
d = j(r) if r else {}
test("Owner reassigns milestone", r is not None and r.status_code == 200, r)
test("Reassigned milestone persisted", str(d.get("milestone", {}).get("id")) == str(m2), r)

r = req("PUT", f"/api/issues/{issue}", token=a, json={"title": "Title only update"})
d = j(r) if r else {}
test("Issue update without milestoneId succeeds", r is not None and r.status_code == 200, r)
test("Omitted milestoneId leaves assignment unchanged",
     str(d.get("milestone", {}).get("id")) == str(m2), r)

r = req("PUT", f"/api/issues/{issue}", token=a, json={"milestoneId": None})
d = j(r) if r else {}
test("Explicit null clears milestone", r is not None and r.status_code == 200, r)
test("Milestone is null after clear", d.get("milestone") is None, r)

r = req("PUT", f"/api/issues/{issue}", token=b, json={"milestoneId": m1})
test("Non-owner cannot assign milestone", r is not None and r.status_code == 403, r)

print("\n" + "-" * 65)
print("CROSS-REPOSITORY PROTECTION")
print("-" * 65)
cross = create_milestone(a, repo2, "Cross Repo Milestone",
                         name="Create cross-repository milestone")
r = req("PUT", f"/api/issues/{issue}", token=a, json={"milestoneId": cross})
test("Cross-repository assignment rejected", r is not None and r.status_code == 400, r)
r = req("GET", f"/api/issues/{issue}", token=b)
d = j(r) if r else {}
test("Rejected cross-repo assignment does not alter issue",
     d.get("milestone") is None, r)

print("\n" + "-" * 65)
print("MISSING RESOURCES")
print("-" * 65)
missing = 999999999
r = req("GET", f"/api/milestones/{missing}")
test("Missing milestone returns 404", r is not None and r.status_code == 404, r)
r = req("PUT", f"/api/milestones/{missing}", token=a, json={"title": "x"})
test("Update missing milestone returns 404", r is not None and r.status_code == 404, r)
r = req("DELETE", f"/api/milestones/{missing}", token=a)
test("Delete missing milestone returns 404", r is not None and r.status_code == 404, r)
r = req("GET", f"/api/repositories/{missing}/milestones")
test("Missing repository returns 404", r is not None and r.status_code == 404, r)
r = req("PUT", f"/api/issues/{missing}", token=a, json={"milestoneId": m1})
test("Update missing issue returns 404", r is not None and r.status_code == 404, r)

print("\n" + "-" * 65)
print("DELETE / SET NULL")
print("-" * 65)
r = req("PUT", f"/api/issues/{issue}", token=a, json={"milestoneId": m2})
test("Assign milestone before deletion", r is not None and r.status_code == 200, r)
r = req("DELETE", f"/api/milestones/{m2}", token=a)
test("Owner deletes milestone", r is not None and r.status_code == 204, r)
r = req("GET", f"/api/issues/{issue}", token=b)
d = j(r) if r else {}
test("Issue survives milestone deletion", r is not None and r.status_code == 200, r)
test("Issue milestone becomes null after deletion", d.get("milestone") is None, r)
r = req("GET", f"/api/milestones/{m2}")
test("Deleted milestone returns 404", r is not None and r.status_code == 404, r)

print("\n" + "-" * 65)
print("CLEANUP")
print("-" * 65)

# m2 was already deleted. Delete remaining milestone objects.
for mid, name in [(cross, "Delete cross-repository milestone"),
                  (same, "Delete second-repository milestone"),
                  (m1, "Delete primary milestone")]:
    if mid:
        r = req("DELETE", f"/api/milestones/{mid}", token=a)
        test(name, r is not None and r.status_code == 204, r)

if issue:
    r = req("DELETE", f"/api/issues/{issue}", token=a)
    test("Delete test issue", r is not None and r.status_code == 204, r)

for rid, name in [(pub, "Delete test public repository"),
                  (priv, "Delete test private repository"),
                  (repo2, "Delete test second repository")]:
    r = req("DELETE", f"/api/repositories/{rid}", token=a)
    test(name, r is not None and r.status_code == 204, r)

print("\n" + "=" * 65)
print("FINAL RESULT")
print("=" * 65)
print(f"PASSED: {passed}")
print(f"FAILED: {failed}")
print(f"TOTAL : {passed + failed}")
print()
print("ALL PHASE 6 API TESTS PASSED" if failed == 0 else "SOME PHASE 6 API TESTS FAILED")
print("=" * 65)

sys.exit(0 if failed == 0 else 1)
