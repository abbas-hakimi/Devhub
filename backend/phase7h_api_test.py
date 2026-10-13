"""End-to-end reliability checks for Phase 7H version-control hardening."""
import concurrent.futures
import getpass
import json
import os
import subprocess
import sys
import threading
import uuid
import urllib.error
import urllib.request

try:
    import requests
except ImportError:
    requests = None

BASE_URL = os.environ.get("PHASE7H_BASE_URL", "http://localhost:8080")
MYSQL_EXE = os.environ.get(
    "MYSQL_EXE",
    r"C:\Users\hakim\OneDrive\Desktop\MySQL\MySQL Server 8.0\bin\mysql.exe",
)
MYSQL_DB = os.environ.get("MYSQL_DB", "version_control")
MYSQL_USER = os.environ.get("MYSQL_USER", "root")
TIMEOUT = 20
passed = failed = 0
tokens = {}
repositories = []


class ApiResponse:
    def __init__(self, status_code, headers, content):
        self.status_code = status_code
        self.headers = headers
        self.content = content
        self.text = content.decode("utf-8", errors="replace")

    def json(self):
        return json.loads(self.text)


def check(name, condition, response=None):
    global passed, failed
    if condition:
        passed += 1
        print(f"[PASS] {name}")
    else:
        failed += 1
        detail = f" HTTP {response.status_code}: {response.text[:250]}" if response else ""
        print(f"[FAIL] {name}{detail}")


def request(method, path, user=None, **kwargs):
    headers = kwargs.pop("headers", {})
    if user in tokens:
        headers["Authorization"] = f"Bearer {tokens[user]}"
    try:
        if requests is not None:
            return requests.request(method, BASE_URL + path, headers=headers, timeout=TIMEOUT, **kwargs)
        payload = kwargs.pop("json", None)
        data = kwargs.pop("data", None)
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers.setdefault("Content-Type", "application/json")
        req = urllib.request.Request(BASE_URL + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as result:
                return ApiResponse(result.status, result.headers, result.read())
        except urllib.error.HTTPError as error:
            return ApiResponse(error.code, error.headers, error.read())
    except Exception as exc:
        print(f"[REQUEST ERROR] {method} {path}: {exc}")
        return None


def response_json(response):
    try:
        return response.json() if response is not None else {}
    except (AttributeError, ValueError):
        return {}


def login(username):
    password = getpass.getpass(f"Password for {username}: ")
    response = request("POST", "/api/auth/login", json={"identifier": username, "password": password})
    token = response_json(response).get("accessToken")
    check(f"Login {username}", response is not None and response.status_code == 200 and bool(token), response)
    if token:
        tokens[username] = token
    return token


def create_repository(user, visibility):
    name = f"phase7h-{visibility.lower()}-{uuid.uuid4().hex[:12]}"
    response = request("POST", "/api/repositories", user,
                       json={"name": name, "description": "Phase 7H reliability test", "visibility": visibility})
    repository_id = response_json(response).get("id")
    check(f"Create {visibility.lower()} test repository", response is not None
          and response.status_code == 201 and repository_id, response)
    if repository_id:
        repositories.append((int(repository_id), user))
    return int(repository_id) if repository_id else None


def initialize(user, repository_id):
    response = request("POST", f"/api/repositories/{repository_id}/git/init", user)
    check(f"Initialize repository {repository_id}", response is not None and response.status_code == 201, response)
    return response_json(response).get("commitHash")


def create_commit(user, repository_id, branch, expected_head, message, path, content):
    return request("POST", f"/api/repositories/{repository_id}/commits", user, json={
        "branch": branch,
        "expectedHeadHash": expected_head,
        "message": message,
        "changes": [{"path": path, "content": content}],
    })


def mysql(password, sql):
    env = os.environ.copy()
    env["MYSQL_PWD"] = password
    try:
        return subprocess.run(
            [MYSQL_EXE, "--batch", "--skip-column-names", "-u", MYSQL_USER, MYSQL_DB, "-e", sql],
            capture_output=True, text=True, timeout=45, env=env,
        )
    finally:
        env.pop("MYSQL_PWD", None)


def object_id_baseline(password):
    result = mysql(password, "SELECT COALESCE(MAX(id),0) FROM trees; SELECT COALESCE(MAX(id),0) FROM blobs;")
    if result.returncode != 0:
        raise RuntimeError("Unable to read object ID baseline: " + result.stderr.strip())
    values = [int(line) for line in result.stdout.splitlines() if line.strip()]
    if len(values) != 2:
        raise RuntimeError("Unexpected MySQL object ID baseline output")
    return values[0], values[1]


def cleanup(password, tree_id_floor, blob_id_floor):
    if not repositories:
        return True
    repo_ids = ",".join(str(repo_id) for repo_id, _ in repositories)
    sql = f"""
CREATE TEMPORARY TABLE phase7h_candidate_trees (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO phase7h_candidate_trees (id)
WITH RECURSIVE candidate(id) AS (
    SELECT root_tree_id FROM commits WHERE repository_id IN ({repo_ids})
    UNION DISTINCT
    SELECT te.child_tree_id FROM tree_entries te JOIN candidate c ON te.tree_id = c.id
      WHERE te.child_tree_id IS NOT NULL
)
SELECT id FROM candidate;
CREATE TEMPORARY TABLE phase7h_candidate_blobs (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO phase7h_candidate_blobs (id)
SELECT DISTINCT te.blob_id FROM tree_entries te
JOIN phase7h_candidate_trees ct ON ct.id = te.tree_id
JOIN blobs b ON b.id = te.blob_id
WHERE te.blob_id IS NOT NULL AND b.id > {int(blob_id_floor)};
DELETE FROM branches WHERE repository_id IN ({repo_ids});
DELETE FROM commits WHERE repository_id IN ({repo_ids}) ORDER BY id DESC;
CREATE TEMPORARY TABLE phase7h_live_trees (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO phase7h_live_trees (id)
WITH RECURSIVE live(id) AS (
    SELECT root_tree_id FROM commits
    UNION DISTINCT
    SELECT te.child_tree_id FROM tree_entries te JOIN live l ON te.tree_id = l.id
      WHERE te.child_tree_id IS NOT NULL
)
SELECT id FROM live;
DELETE te FROM tree_entries te
JOIN phase7h_candidate_trees ct ON ct.id = te.tree_id
LEFT JOIN phase7h_live_trees lt ON lt.id = te.tree_id
WHERE lt.id IS NULL AND te.tree_id > {int(tree_id_floor)};
DELETE t FROM trees t
JOIN phase7h_candidate_trees ct ON ct.id = t.id
LEFT JOIN phase7h_live_trees lt ON lt.id = t.id
WHERE lt.id IS NULL AND t.id > {int(tree_id_floor)};
DELETE b FROM blobs b
JOIN phase7h_candidate_blobs cb ON cb.id = b.id
LEFT JOIN tree_entries te ON te.blob_id = b.id
WHERE te.id IS NULL;
"""
    result = mysql(password, sql)
    if result.returncode != 0:
        print("[CLEANUP ERROR] MySQL cleanup failed:", result.stderr.strip())
        return False
    ok = True
    for repository_id, owner in repositories:
        response = request("DELETE", f"/api/repositories/{repository_id}", owner)
        deleted = response is not None and response.status_code == 204
        check(f"Cleanup test repository {repository_id}", deleted, response)
        ok = ok and deleted
    return ok


def run_checks(user_a, user_b):
    public_id = create_repository(user_a, "PUBLIC")
    if not public_id:
        return
    initial = initialize(user_a, public_id)
    if not initial:
        return

    created = request("POST", f"/api/repositories/{public_id}/branches", user_a,
                     json={"name": "feature/isolation"})
    check("Create feature branch at main HEAD", created is not None and created.status_code == 201
          and response_json(created).get("headCommit", {}).get("commitHash") == initial, created)
    feature = create_commit(user_a, public_id, "feature/isolation", initial,
                            "Feature change", "feature.txt", "feature content")
    feature_hash = response_json(feature).get("commitHash")
    check("Commit to feature branch", feature is not None and feature.status_code == 201 and feature_hash, feature)

    branches = request("GET", f"/api/repositories/{public_id}/branches")
    branch_map = {item.get("name"): item.get("headCommit", {}).get("commitHash")
                  for item in response_json(branches) if isinstance(response_json(branches), list)}
    check("Feature commit leaves main HEAD unchanged", branch_map.get("main") == initial
          and branch_map.get("feature/isolation") == feature_hash, branches)
    feature_history = request("GET", f"/api/repositories/{public_id}/commits?branch=feature%2Fisolation")
    main_history = request("GET", f"/api/repositories/{public_id}/commits?branch=main")
    feature_items = response_json(feature_history).get("commits", [])
    main_items = response_json(main_history).get("commits", [])
    check("Feature history contains its commit and parent", feature_history is not None
          and feature_history.status_code == 200 and len(feature_items) == 2
          and feature_items[0].get("commitHash") == feature_hash
          and feature_items[0].get("parentCommitHash") == initial, feature_history)
    check("Main history excludes feature-only commit", main_history is not None
          and main_history.status_code == 200 and [item.get("commitHash") for item in main_items] == [initial], main_history)
    check("Anonymous user can read public history", request("GET", f"/api/repositories/{public_id}/commits")
          .status_code == 200)

    other_id = create_repository(user_a, "PUBLIC")
    if other_id:
        foreign_hash = initialize(user_a, other_id)
        foreign_branch = request("POST", f"/api/repositories/{public_id}/branches", user_a,
                                 json={"name": "foreign-source", "fromCommitHash": foreign_hash})
        check("Reject cross-repository branch source commit", foreign_branch is not None
              and foreign_branch.status_code == 404, foreign_branch)

    anonymous_commit = request("POST", f"/api/repositories/{public_id}/commits",
                               json={"branch": "main", "message": "anonymous",
                                     "changes": [{"path": "x", "content": "x"}]})
    check("Anonymous commit is unauthorized", anonymous_commit is not None
          and anonymous_commit.status_code == 401, anonymous_commit)
    denied = create_commit(user_b, public_id, "main", initial, "non-owner", "denied.txt", "no")
    check("Non-owner cannot commit", denied is not None and denied.status_code == 403, denied)

    barrier = threading.Barrier(2)

    def race(path, message):
        barrier.wait(timeout=10)
        return create_commit(user_a, public_id, "main", initial, message, path, message)

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(race, "race-a.txt", "Race A"),
                   executor.submit(race, "race-b.txt", "Race B")]
        results = [future.result(timeout=45) for future in futures]
        statuses = [result.status_code if result is not None else 0 for result in results]
    check("Concurrent writes to one expected HEAD yield one winner", sorted(statuses) == [201, 409])

    too_deep = "/".join(["x"] * 65)
    deep = create_commit(user_a, public_id, "main", None, "Too deep", too_deep, "x")
    check("Reject excessive tree nesting", deep is not None and deep.status_code == 400, deep)
    invalid_hash = request("GET", "/api/commits/not-a-sha256/tree")
    check("Reject malformed commit hash", invalid_hash is not None and invalid_hash.status_code == 400, invalid_hash)
    traversal = request("GET", f"/api/commits/{initial}/tree/%2E%2E")
    check("Reject encoded traversal securely", traversal is not None and traversal.status_code in (400, 401), traversal)

    private_id = create_repository(user_a, "PRIVATE")
    if private_id:
        initialize(user_a, private_id)
        hidden = request("GET", f"/api/repositories/{private_id}/commits", user_b)
        check("Hide private history from non-owner", hidden is not None and hidden.status_code == 404, hidden)


def main():
    print("PHASE 7H — VERSION-CONTROL ENGINE RELIABILITY API TEST")
    print(f"Backend: {BASE_URL}")
    if not login("postmanA") or not login("postmanB"):
        return 1
    db_password = getpass.getpass("MySQL cleanup password: ")
    try:
        tree_floor, blob_floor = object_id_baseline(db_password)
    except Exception as error:
        print("[SETUP ERROR]", str(error))
        return 1

    try:
        run_checks("postmanA", "postmanB")
    except Exception as error:
        check("HTTP test run completed without an unexpected error", False)
        print(f"[RUNNER ERROR] {type(error).__name__}")
    finally:
        clean = cleanup(db_password, tree_floor, blob_floor)
        if not clean:
            check("All test data cleaned up", False)
    print("\n" + "=" * 58)
    print(f"PASSED: {passed}")
    print(f"FAILED: {failed}")
    print(f"TOTAL : {passed + failed}")
    print("ALL PHASE 7H API TESTS PASSED" if failed == 0 else "SOME PHASE 7H API TESTS FAILED")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
