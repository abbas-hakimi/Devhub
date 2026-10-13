"""HTTP acceptance checks for Phase 8C comparison and PR diff APIs."""
import getpass
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid

BASE_URL = os.environ.get("PHASE8C_BASE_URL", "http://localhost:8080")
MYSQL_EXE = os.environ.get("MYSQL_EXE", r"C:\Users\hakim\OneDrive\Desktop\MySQL\MySQL Server 8.0\bin\mysql.exe")
MYSQL_DB = os.environ.get("MYSQL_DB", "version_control")
MYSQL_USER = os.environ.get("MYSQL_USER", "root")
tokens, repositories = {}, []
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
    request = urllib.request.Request(BASE_URL + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
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
        suffix = f" HTTP {response.status_code}: {response.text[:250]}" if response else ""
        print("[FAIL] " + label + suffix)


def login(username):
    password = getpass.getpass(f"Password for {username}: ")
    response = call("POST", "/api/auth/login", payload={"identifier": username, "password": password})
    token = body(response).get("accessToken")
    check("Login " + username, response is not None and response.status_code == 200 and token, response)
    if token:
        tokens[username] = token
    return bool(token)


def mysql(password, sql):
    env = os.environ.copy()
    env["MYSQL_PWD"] = password
    try:
        return subprocess.run([MYSQL_EXE, "--batch", "--skip-column-names", "-u", MYSQL_USER, MYSQL_DB, "-e", sql],
                              capture_output=True, text=True, timeout=60, env=env)
    finally:
        env.pop("MYSQL_PWD", None)


def cleanup(password, tree_floor, blob_floor):
    if not repositories:
        return
    ids = ",".join(str(repo) for repo, _ in repositories)
    sql = f"""
CREATE TEMPORARY TABLE p8c_trees (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO p8c_trees WITH RECURSIVE walk(id) AS (
 SELECT root_tree_id FROM commits WHERE repository_id IN ({ids}) UNION DISTINCT
 SELECT e.child_tree_id FROM tree_entries e JOIN walk w ON e.tree_id=w.id WHERE e.child_tree_id IS NOT NULL
) SELECT id FROM walk;
CREATE TEMPORARY TABLE p8c_blobs (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO p8c_blobs SELECT DISTINCT e.blob_id FROM tree_entries e JOIN p8c_trees t ON t.id=e.tree_id
 JOIN blobs b ON b.id=e.blob_id WHERE e.blob_id IS NOT NULL AND b.id>{int(blob_floor)};
DELETE FROM pull_requests WHERE repository_id IN ({ids});
DELETE FROM branches WHERE repository_id IN ({ids});
DELETE FROM commits WHERE repository_id IN ({ids}) ORDER BY id DESC;
CREATE TEMPORARY TABLE p8c_live (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO p8c_live WITH RECURSIVE walk(id) AS (
 SELECT root_tree_id FROM commits UNION DISTINCT
 SELECT e.child_tree_id FROM tree_entries e JOIN walk w ON e.tree_id=w.id WHERE e.child_tree_id IS NOT NULL
) SELECT id FROM walk;
DELETE e FROM tree_entries e JOIN p8c_trees t ON t.id=e.tree_id LEFT JOIN p8c_live l ON l.id=e.tree_id WHERE l.id IS NULL AND e.tree_id>{int(tree_floor)};
DELETE t FROM trees t JOIN p8c_trees c ON c.id=t.id LEFT JOIN p8c_live l ON l.id=t.id WHERE l.id IS NULL AND t.id>{int(tree_floor)};
DELETE b FROM blobs b JOIN p8c_blobs c ON c.id=b.id LEFT JOIN tree_entries e ON e.blob_id=b.id WHERE e.id IS NULL;
"""
    result = mysql(password, sql)
    if result.returncode:
        print("[CLEANUP ERROR] " + result.stderr.strip())
        return
    for repo, owner in repositories:
        response = call("DELETE", f"/api/repositories/{repo}", owner)
        check(f"Cleanup test repository {repo}", response is not None and response.status_code == 204, response)


def run():
    name = "phase8c-" + uuid.uuid4().hex[:14]
    created = call("POST", "/api/repositories", "postmanA", {"name": name, "visibility": "PUBLIC", "description": "Phase 8C API test"})
    repo_id = body(created).get("id")
    check("Create public test repository", created is not None and created.status_code == 201 and repo_id, created)
    if not repo_id:
        return
    repo_id = int(repo_id)
    repositories.append((repo_id, "postmanA"))
    initialized = call("POST", f"/api/repositories/{repo_id}/git/init", "postmanA")
    initial = body(initialized).get("commitHash")
    check("Initialize repository", initialized is not None and initialized.status_code == 201 and initial, initialized)
    if not initial:
        return
    branch = call("POST", f"/api/repositories/{repo_id}/branches", "postmanA", {"name": "feature/phase8c", "fromCommitHash": initial})
    check("Create feature branch", branch is not None and branch.status_code == 201, branch)
    feature = call("POST", f"/api/repositories/{repo_id}/commits", "postmanA", {
        "branch": "feature/phase8c", "expectedHeadHash": initial, "message": "Add files",
        "changes": [{"path": "nested/hello.txt", "content": "hello\nworld\n"},
                    {"path": "asset.bin", "contentBase64": "AAEC/w=="}],
    })
    feature_hash = body(feature).get("commitHash")
    check("Create feature commit", feature is not None and feature.status_code == 201 and feature_hash, feature)
    comparison = call("GET", f"/api/repositories/{repo_id}/compare?base=main&head=feature%2Fphase8c")
    comparison_body = body(comparison)
    changes = comparison_body.get("changes", [])
    check("Anonymous public comparison", comparison is not None and comparison.status_code == 200, comparison)
    check("Comparison ancestry and counts", comparison_body.get("baseCommitHash") == initial
          and comparison_body.get("headCommitHash") == feature_hash and comparison_body.get("aheadBy") == 1
          and comparison_body.get("behindBy") == 0 and comparison_body.get("filesChanged") == 2, comparison)
    check("Nested text and binary metadata", any(c.get("path") == "nested/hello.txt" and c.get("status") == "ADDED" and c.get("patch") for c in changes)
          and any(c.get("path") == "asset.bin" and c.get("binary") is True and c.get("patch") is None for c in changes), comparison)
    check("Changed file content hashes are returned", all(c.get("newHash") for c in changes)
          and all(c.get("oldHash") is None for c in changes), comparison)
    private = call("POST", "/api/repositories", "postmanA", {
        "name": "phase8c-private-" + uuid.uuid4().hex[:10], "visibility": "PRIVATE"})
    private_id = body(private).get("id")
    check("Create private test repository", private is not None and private.status_code == 201 and private_id, private)
    if private_id:
        repositories.append((int(private_id), "postmanA"))
        private_init = call("POST", f"/api/repositories/{private_id}/git/init", "postmanA")
        check("Initialize private repository", private_init is not None and private_init.status_code == 201, private_init)
        private_compare = f"/api/repositories/{private_id}/compare?base=main&head=main"
        check("Private comparison hidden from anonymous users", (lambda r: r is not None and r.status_code == 404)(call("GET", private_compare)))
        check("Private comparison hidden from non-owner", (lambda r: r is not None and r.status_code == 404)(call("GET", private_compare, "postmanB")))
        check("Private comparison visible to owner", (lambda r: r is not None and r.status_code == 200)(call("GET", private_compare, "postmanA")))
    anon_write = call("POST", f"/api/repositories/{repo_id}/branches", payload={"name": "denied", "fromCommitHash": initial})
    check("Anonymous write remains unauthorized", anon_write is not None and anon_write.status_code == 401, anon_write)
    pr_response = call("POST", f"/api/repositories/{repo_id}/pull-requests", "postmanA", {
        "title": "Phase 8C diff", "sourceBranch": "feature/phase8c", "targetBranch": "main"})
    pr_id = body(pr_response).get("id")
    check("Create pull request", pr_response is not None and pr_response.status_code == 201 and pr_id, pr_response)
    if pr_id:
        pr_diff = call("GET", f"/api/pull-requests/{pr_id}/diff")
        check("Anonymous public PR diff", pr_diff is not None and pr_diff.status_code == 200
              and body(pr_diff).get("headCommitHash") == feature_hash and body(pr_diff).get("filesChanged") == 2, pr_diff)


def main():
    print("PHASE 8C — BRANCH COMPARISON HTTP TEST")
    print("Backend:", BASE_URL)
    if not login("postmanA") or not login("postmanB"):
        return 1
    password = getpass.getpass("MySQL cleanup password: ")
    baseline = mysql(password, "SELECT COALESCE(MAX(id),0) FROM trees; SELECT COALESCE(MAX(id),0) FROM blobs;")
    if baseline.returncode:
        print("[SETUP ERROR] Unable to read object-ID baseline:", baseline.stderr.strip())
        return 1
    values = [int(value) for value in baseline.stdout.splitlines() if value.strip()]
    if len(values) != 2:
        print("[SETUP ERROR] Unexpected MySQL baseline response")
        return 1
    try:
        run()
    finally:
        cleanup(password, values[0], values[1])
        print(f"\nResults: {passed} passed, {failed} failed")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
