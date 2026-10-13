"""HTTP acceptance checks for Phase 8A/8B pull request lifecycle."""
import getpass
import json
import os
import subprocess
import uuid
import urllib.error
import urllib.request

try:
    import requests
except ImportError:
    requests = None

BASE_URL = os.environ.get("PHASE8AB_BASE_URL", "http://localhost:8080")
MYSQL_EXE = os.environ.get("MYSQL_EXE", r"C:\Users\hakim\OneDrive\Desktop\MySQL\MySQL Server 8.0\bin\mysql.exe")
MYSQL_DB = os.environ.get("MYSQL_DB", "version_control")
MYSQL_USER = os.environ.get("MYSQL_USER", "root")
TIMEOUT = 20
tokens = {}
repositories = []
passed = failed = 0


class Response:
    def __init__(self, status, headers, content):
        self.status_code, self.headers, self.content = status, headers, content
        self.text = content.decode("utf-8", errors="replace")

    def json(self):
        return json.loads(self.text)


def call(method, path, user=None, payload=None):
    headers = {"Content-Type": "application/json"} if payload is not None else {}
    if user in tokens:
        headers["Authorization"] = "Bearer " + tokens[user]
    data = json.dumps(payload).encode() if payload is not None else None
    try:
        if requests is not None:
            return requests.request(method, BASE_URL + path, headers=headers, json=payload, timeout=TIMEOUT)
        req = urllib.request.Request(BASE_URL + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as response:
                return Response(response.status, response.headers, response.read())
        except urllib.error.HTTPError as error:
            return Response(error.code, error.headers, error.read())
    except Exception as error:
        print(f"[REQUEST ERROR] {method} {path}: {error}")
        return None


def body(response):
    try:
        return response.json() if response else {}
    except (ValueError, AttributeError):
        return {}


def check(label, condition, response=None):
    global passed, failed
    if condition:
        passed += 1
        print("[PASS] " + label)
    else:
        failed += 1
        extra = f" HTTP {response.status_code}: {response.text[:250]}" if response else ""
        print("[FAIL] " + label + extra)


def login(username):
    password = getpass.getpass(f"Password for {username}: ")
    response = call("POST", "/api/auth/login", payload={"identifier": username, "password": password})
    token = body(response).get("accessToken")
    check("Login " + username, response is not None and response.status_code == 200 and token, response)
    if token:
        tokens[username] = token
    return bool(token)


def create_repo(user, visibility):
    name = "phase8ab-" + uuid.uuid4().hex[:14]
    response = call("POST", "/api/repositories", user, {"name": name, "visibility": visibility,
                                                           "description": "Phase 8A/8B API test"})
    repo_id = body(response).get("id")
    check("Create " + visibility.lower() + " test repository", response is not None and response.status_code == 201 and repo_id, response)
    if repo_id:
        repositories.append((int(repo_id), user))
    return int(repo_id) if repo_id else None


def initialize(user, repo):
    response = call("POST", f"/api/repositories/{repo}/git/init", user)
    check("Initialize test repository", response is not None and response.status_code == 201, response)
    return body(response).get("commitHash")


def branch(user, repo, name, source_hash):
    response = call("POST", f"/api/repositories/{repo}/branches", user,
                    {"name": name, "fromCommitHash": source_hash})
    check("Create branch " + name, response is not None and response.status_code == 201, response)
    return source_hash if response is not None and response.status_code == 201 else None


def commit(user, repo, name, old_hash, label):
    response = call("POST", f"/api/repositories/{repo}/commits", user, {
        "branch": name, "expectedHeadHash": old_hash, "message": label,
        "changes": [{"path": label.replace(" ", "-") + ".txt", "content": "content-" + label}],
    })
    new_hash = body(response).get("commitHash")
    check("Create commit " + label, response is not None and response.status_code == 201 and new_hash, response)
    return new_hash


def mysql(password, sql):
    env = os.environ.copy()
    env["MYSQL_PWD"] = password
    try:
        return subprocess.run([MYSQL_EXE, "--batch", "--skip-column-names", "-u", MYSQL_USER, MYSQL_DB, "-e", sql],
                              capture_output=True, text=True, timeout=45, env=env)
    finally:
        env.pop("MYSQL_PWD", None)


def cleanup(password, tree_floor, blob_floor):
    if not repositories:
        return
    ids = ",".join(str(repo) for repo, _ in repositories)
    # Candidate trees/blobs are limited to object IDs created after this run's baseline.
    # Shared objects still referenced by other commits are retained.
    sql = f"""
CREATE TEMPORARY TABLE p8_trees (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO p8_trees WITH RECURSIVE walk(id) AS (
 SELECT root_tree_id FROM commits WHERE repository_id IN ({ids}) UNION DISTINCT
 SELECT e.child_tree_id FROM tree_entries e JOIN walk w ON e.tree_id=w.id WHERE e.child_tree_id IS NOT NULL
) SELECT id FROM walk;
CREATE TEMPORARY TABLE p8_blobs (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO p8_blobs SELECT DISTINCT e.blob_id FROM tree_entries e JOIN p8_trees t ON t.id=e.tree_id
 JOIN blobs b ON b.id=e.blob_id WHERE e.blob_id IS NOT NULL AND b.id>{int(blob_floor)};
DELETE FROM pull_requests WHERE repository_id IN ({ids});
DELETE FROM branches WHERE repository_id IN ({ids});
DELETE FROM commits WHERE repository_id IN ({ids}) ORDER BY id DESC;
CREATE TEMPORARY TABLE p8_live (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO p8_live WITH RECURSIVE walk(id) AS (
 SELECT root_tree_id FROM commits UNION DISTINCT
 SELECT e.child_tree_id FROM tree_entries e JOIN walk w ON e.tree_id=w.id WHERE e.child_tree_id IS NOT NULL
) SELECT id FROM walk;
DELETE e FROM tree_entries e JOIN p8_trees t ON t.id=e.tree_id LEFT JOIN p8_live l ON l.id=e.tree_id
 WHERE l.id IS NULL AND e.tree_id>{int(tree_floor)};
DELETE t FROM trees t JOIN p8_trees c ON c.id=t.id LEFT JOIN p8_live l ON l.id=t.id
 WHERE l.id IS NULL AND t.id>{int(tree_floor)};
DELETE b FROM blobs b JOIN p8_blobs c ON c.id=b.id LEFT JOIN tree_entries e ON e.blob_id=b.id WHERE e.id IS NULL;
"""
    result = mysql(password, sql)
    if result.returncode:
        print("[CLEANUP ERROR] " + result.stderr.strip())
        return
    for repo, owner in repositories:
        response = call("DELETE", f"/api/repositories/{repo}", owner)
        check(f"Cleanup test repository {repo}", response is not None and response.status_code == 204, response)


def run(user_a, user_b):
    public = create_repo(user_a, "PUBLIC")
    if not public:
        return
    base = initialize(user_a, public)
    if not base:
        return
    branch(user_a, public, "feature/auth", base)
    feature = commit(user_a, public, "feature/auth", base, "phase8 feature")
    endpoint = f"/api/repositories/{public}/pull-requests"
    request = {"title": "API feature", "description": "Original details", "sourceBranch": "feature/auth", "targetBranch": "main"}
    anon = call("POST", endpoint, payload=request)
    check("Anonymous creation gets 401", anon is not None and anon.status_code == 401, anon)
    denied = call("POST", endpoint, user_b, request)
    check("Non-owner creation gets 403", denied is not None and denied.status_code == 403, denied)
    created = call("POST", endpoint, user_a, request)
    pr = body(created)
    check("Create PR captures branch heads and #1", created is not None and created.status_code == 201
          and pr.get("number") == 1 and pr.get("sourceHeadHashAtCreation") == feature
          and pr.get("targetHeadHashAtCreation") == base, created)
    if not pr.get("id"):
        return
    pr_url = f"/api/pull-requests/{pr['id']}"
    check("Anonymous public list is readable", (lambda r: r is not None and r.status_code == 200)(call("GET", endpoint)))
    check("Anonymous public detail is readable", (lambda r: r is not None and r.status_code == 200)(call("GET", pr_url)))
    listing = call("GET", endpoint + "?state=OPEN&page=0&size=1")
    check("State filter and pagination", listing is not None and listing.status_code == 200
          and body(listing).get("totalElements") == 1 and body(listing).get("size") == 1, listing)
    edit = call("PUT", pr_url, user_a, {"title": "Edited PR"})
    check("Partial title update", edit is not None and edit.status_code == 200 and body(edit).get("title") == "Edited PR", edit)
    clear = call("PUT", pr_url, user_a, {"description": None})
    check("Description can be cleared", clear is not None and clear.status_code == 200 and body(clear).get("description") is None, clear)
    bad_edit = call("PUT", pr_url, user_a, {"title": " "})
    check("Blank title is rejected", bad_edit is not None and bad_edit.status_code == 400, bad_edit)
    duplicate = call("POST", endpoint, user_a, request)
    check("Duplicate open branch pair gets 409", duplicate is not None and duplicate.status_code == 409, duplicate)
    close_denied = call("POST", pr_url + "/close", user_b)
    check("Non-owner close gets 403", close_denied is not None and close_denied.status_code == 403, close_denied)
    closed = call("POST", pr_url + "/close", user_a)
    check("Owner closes PR", closed is not None and closed.status_code == 200 and body(closed).get("state") == "CLOSED", closed)
    reopened_denied = call("POST", pr_url + "/reopen", user_b)
    check("Non-owner reopen gets 403", reopened_denied is not None and reopened_denied.status_code == 403, reopened_denied)
    reopened = call("POST", pr_url + "/reopen", user_a)
    check("Owner reopens PR", reopened is not None and reopened.status_code == 200
          and body(reopened).get("state") == "OPEN" and body(reopened).get("closedAt") is None, reopened)

    private = create_repo(user_a, "PRIVATE")
    if private:
        initialize(user_a, private)
        hidden = call("GET", f"/api/repositories/{private}/pull-requests", user_b)
        check("Private PR list is hidden from non-owner", hidden is not None and hidden.status_code == 404, hidden)
    invalid = call("POST", endpoint, user_a, {"title": "bad", "sourceBranch": "main", "targetBranch": "main"})
    check("Same source and target is rejected", invalid is not None and invalid.status_code == 400, invalid)
    missing = call("GET", "/api/pull-requests/9223372036854770000")
    check("Missing PR returns 404", missing is not None and missing.status_code == 404, missing)


def main():
    global passed, failed
    print("PHASE 8A/8B — PULL REQUEST HTTP TEST")
    print("Backend:", BASE_URL)
    if not login("postmanA") or not login("postmanB"):
        return 1
    password = getpass.getpass("MySQL cleanup password: ")
    baseline = mysql(password, "SELECT COALESCE(MAX(id),0) FROM trees; SELECT COALESCE(MAX(id),0) FROM blobs;")
    if baseline.returncode:
        print("[SETUP ERROR] Unable to read object-ID baseline:", baseline.stderr.strip())
        return 1
    values = [int(x) for x in baseline.stdout.splitlines() if x.strip()]
    if len(values) != 2:
        print("[SETUP ERROR] Unexpected MySQL object-ID response")
        return 1
    try:
        run("postmanA", "postmanB")
    finally:
        cleanup(password, values[0], values[1])
        print(f"\nResults: {passed} passed, {failed} failed")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
