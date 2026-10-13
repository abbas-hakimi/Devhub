"""End-to-end HTTP checks for Phase 7G commit/tree/blob browsing."""
import getpass
import os
import subprocess
import sys
import uuid
import json
from pathlib import Path
import urllib.error
import urllib.request

try:
    import requests
except ImportError:
    requests = None

BASE_URL = os.environ.get("PHASE7G_BASE_URL", "http://localhost:8080")
MYSQL_EXE = r"C:\Users\hakim\OneDrive\Desktop\MySQL\MySQL Server 8.0\bin\mysql.exe"
MYSQL_DB = "version_control"
MYSQL_USER = "root"
TIMEOUT = 15
passed = failed = 0
repository_ids = []
tokens = {}


def check(name, condition, response=None):
    global passed, failed
    if condition:
        passed += 1
        print(f"[PASS] {name}")
    else:
        failed += 1
        detail = f" HTTP {response.status_code}: {response.text[:300]}" if response is not None else ""
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


class ApiResponse:
    def __init__(self, status_code, headers, content):
        self.status_code = status_code
        self.headers = headers
        self.content = content
        self.text = content.decode("utf-8", errors="replace")

    def json(self):
        return json.loads(self.text)


def login(username):
    password = getpass.getpass(f"Password for {username}: ")
    response = request("POST", "/api/auth/login", json={"identifier": username, "password": password})
    data = response.json() if response is not None and response.headers.get("Content-Type", "").startswith("application/json") else {}
    token = data.get("accessToken")
    check(f"Login {username}", response is not None and response.status_code == 200 and bool(token), response)
    if token:
        tokens[username] = token
    return token


def create_repository(user, visibility):
    name = f"phase7g-{visibility.lower()}-{uuid.uuid4().hex[:12]}"
    response = request("POST", "/api/repositories", user,
                       json={"name": name, "description": "Phase 7G API test", "visibility": visibility})
    data = response.json() if response is not None else {}
    repository_id = data.get("id")
    check(f"Create {visibility.lower()} test repository", response is not None and response.status_code == 201 and repository_id, response)
    if repository_id:
        repository_ids.append((repository_id, user))
    return repository_id


def initialize(user, repository_id):
    return request("POST", f"/api/repositories/{repository_id}/git/init", user)


def add_commit(user, repository_id, message, changes):
    return request("POST", f"/api/repositories/{repository_id}/commits", user,
                   json={"branch": "main", "message": message, "changes": changes})


def body(response):
    try:
        return response.json()
    except (AttributeError, ValueError):
        return {}


def run_public_checks(user_a, user_b):
    repository_id = create_repository(user_a, "PUBLIC")
    if not repository_id:
        return
    init = initialize(user_a, repository_id)
    check("Initialize public repository", init is not None and init.status_code == 201, init)
    initial_hash = body(init).get("commitHash") if init is not None else None
    root_hash = body(init).get("rootTreeHash") if init is not None else None

    first = add_commit(user_a, repository_id, "Add files", [
        {"path": "README.md", "content": "Phase 7G README"},
        {"path": "src/main.java", "content": "class Main {}"},
        {"path": "src/utils/helper.txt", "content": "nested"},
        {"path": "image.bin", "contentBase64": "AAECAwT/"},
        {"path": "\uE000.txt", "content": "bmp"},
        {"path": "😀.txt", "content": "supplementary"},
    ])
    first_hash = body(first).get("commitHash") if first is not None else None
    check("Create first test commit", first is not None and first.status_code == 201 and first_hash, first)
    second = add_commit(user_a, repository_id, "Advance history", [
        {"path": "latest.txt", "content": "newest"},
    ])
    second_hash = body(second).get("commitHash") if second is not None else None
    check("Create second test commit", second is not None and second.status_code == 201 and second_hash, second)

    branches = request("GET", f"/api/repositories/{repository_id}/branches", user_a)
    branch_data = body(branches)
    main = next((item for item in branch_data if item.get("name") == "main"), {}) if isinstance(branch_data, list) else {}
    check("Read branch HEAD", branches is not None and branches.status_code == 200 and main.get("headCommit", {}).get("commitHash") == second_hash, branches)

    history = request("GET", f"/api/repositories/{repository_id}/commits?branch=main&page=0&size=20")
    commits = body(history).get("commits", []) if history is not None else []
    check("Anonymous public history newest to oldest", history is not None and history.status_code == 200
          and [c.get("commitHash") for c in commits] == [second_hash, first_hash, initial_hash], history)
    single = request("GET", f"/api/commits/{first_hash}")
    check("Get specific commit", single is not None and single.status_code == 200
          and body(single).get("commitHash") == first_hash, single)

    root = request("GET", f"/api/commits/{first_hash}/tree")
    root_entries = body(root).get("entries", []) if root is not None else []
    check("Read root tree with README and directory", root is not None and root.status_code == 200
          and any(e.get("name") == "README.md" and e.get("type") == "FILE" for e in root_entries)
          and any(e.get("name") == "src" and e.get("type") == "DIRECTORY" for e in root_entries), root)
    check("Root tree contains file hash and size", any(e.get("name") == "README.md" and e.get("blobHash") and e.get("size") == len("Phase 7G README") for e in root_entries), root)
    check("Root tree contains child tree hash", any(e.get("name") == "src" and e.get("treeHash") for e in root_entries), root)
    check("Root tree follows unsigned UTF-8 ordering", [e.get("name") for e in root_entries]
          == ["README.md", "image.bin", "src", "\uE000.txt", "😀.txt"], root)

    src = request("GET", f"/api/commits/{first_hash}/tree/src")
    check("Browse src tree", src is not None and src.status_code == 200 and body(src).get("path") == "src", src)
    nested = request("GET", f"/api/commits/{first_hash}/tree/src/utils")
    check("Browse nested directory", nested is not None and nested.status_code == 200
          and any(e.get("name") == "helper.txt" for e in body(nested).get("entries", [])), nested)

    readme = request("GET", f"/api/commits/{first_hash}/blob/README.md")
    check("Read README exact bytes", readme is not None and readme.status_code == 200
          and readme.content == b"Phase 7G README", readme)
    expected_binary = bytes([0, 1, 2, 3, 4, 255])
    binary = request("GET", f"/api/commits/{first_hash}/blob/image.bin")
    check("Read binary bytes exactly", binary is not None and binary.status_code == 200 and binary.content == expected_binary, binary)
    check("Blob Content-Length matches", binary is not None and binary.status_code == 200
          and binary.headers.get("Content-Length") == str(len(expected_binary)), binary)
    etag = binary.headers.get("ETag") if binary is not None else None
    check("Blob ETag is SHA-256 hash", bool(etag and len(etag) == 66 and etag[0] == '"' and etag[-1] == '"'), binary)
    conditional = request("GET", f"/api/commits/{first_hash}/blob/image.bin", headers={"If-None-Match": etag or ""})
    check("If-None-Match returns 304", conditional is not None and conditional.status_code == 304
          and not conditional.content, conditional)

    for path, expected in [("does-not-exist", (404,)), ("src", (404,)),
                           ("%2E%2E/secret", (400, 401)),
                           ("src%5C..%5Csecret", (400,)), ("C:%5Csecret", (400,))]:
        response = request("GET", f"/api/commits/{first_hash}/blob/{path}")
        # Encoded dot segments can be rejected by Spring Security's authenticated fallback
        # before the browse controller is reached. Both 401 (early security rejection) and
        # 400 (application path validation) are secure outcomes for this traversal attempt.
        check(f"Reject blob path {path}", response is not None and response.status_code in expected, response)
    traversal = request("GET", f"/api/commits/{first_hash}/tree/%2E%2E")
    # Same encoded-dot-segment handling as the blob route: do not weaken anonymous access
    # or traversal protections merely to force the request through application validation.
    check("Reject traversal directory", traversal is not None and traversal.status_code in (400, 401), traversal)
    check("Other user can browse public history", (lambda r: r is not None and r.status_code == 200)(
        request("GET", f"/api/repositories/{repository_id}/commits", user_b)))


def run_private_checks(user_a, user_b):
    repository_id = create_repository(user_a, "PRIVATE")
    if not repository_id:
        return
    init = initialize(user_a, repository_id)
    check("Initialize private repository", init is not None and init.status_code == 201, init)
    secret = add_commit(user_a, repository_id, "Add private file", [
        {"path": "secret.txt", "content": "private"},
    ])
    check("Create private test file", secret is not None and secret.status_code == 201, secret)
    commit_hash = body(secret).get("commitHash") if secret is not None else None
    for label, user in [("anonymous", None), ("non-owner", user_b)]:
        history = request("GET", f"/api/repositories/{repository_id}/commits", user)
        check(f"Private history hidden from {label}", history is not None and history.status_code == 404, history)
        commit = request("GET", f"/api/commits/{commit_hash}", user)
        check(f"Private commit hidden from {label}", commit is not None and commit.status_code == 404, commit)
        tree = request("GET", f"/api/commits/{commit_hash}/tree", user)
        check(f"Private tree hidden from {label}", tree is not None and tree.status_code == 404, tree)
        blob = request("GET", f"/api/commits/{commit_hash}/blob/secret.txt", user)
        check(f"Private blob hidden from {label}", blob is not None and blob.status_code == 404, blob)
    owner_history = request("GET", f"/api/repositories/{repository_id}/commits", user_a)
    check("Private repository owner can browse history", owner_history is not None and owner_history.status_code == 200, owner_history)


def cleanup(db_password):
    """Remove only test repository rows in one MySQL process/connection, then use normal API delete."""
    if not repository_ids:
        return True
    id_list = ",".join(str(int(repository_id)) for repository_id, _ in repository_ids)
    sql = f"""
DELETE FROM branches WHERE repository_id IN ({id_list});
DELETE FROM commits WHERE repository_id IN ({id_list}) ORDER BY id DESC;
CREATE TEMPORARY TABLE phase7g_reachable_trees (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO phase7g_reachable_trees (id)
WITH RECURSIVE reachable(id) AS (
    SELECT root_tree_id FROM commits
    UNION DISTINCT
    SELECT te.child_tree_id FROM tree_entries te JOIN reachable r ON te.tree_id = r.id
      WHERE te.child_tree_id IS NOT NULL
)
SELECT id FROM reachable;
DELETE FROM tree_entries WHERE tree_id NOT IN (SELECT id FROM phase7g_reachable_trees);
DELETE FROM trees WHERE id NOT IN (SELECT id FROM phase7g_reachable_trees);
DROP TEMPORARY TABLE phase7g_reachable_trees;
DELETE b FROM blobs b LEFT JOIN tree_entries te ON te.blob_id = b.id WHERE te.id IS NULL;
"""
    env = os.environ.copy()
    env["MYSQL_PWD"] = db_password
    try:
        result = subprocess.run([MYSQL_EXE, "--batch", "--skip-column-names", "-u", MYSQL_USER, MYSQL_DB, "-e", sql],
                                capture_output=True, text=True, timeout=30, env=env)
    finally:
        env.pop("MYSQL_PWD", None)
    if result.returncode != 0:
        print("[CLEANUP ERROR] MySQL cleanup failed:", result.stderr.strip())
        return False
    ok = True
    for repository_id, owner in repository_ids:
        response = request("DELETE", f"/api/repositories/{repository_id}", owner)
        deleted = response is not None and response.status_code == 204
        check(f"Cleanup repository {repository_id}", deleted, response)
        ok = ok and deleted
    return ok


def configured_database_password():
    properties = Path(__file__).parent / "src" / "main" / "resources" / "application.properties"
    if properties.exists():
        for line in properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("spring.datasource.password="):
                return line.partition("=")[2]
    return getpass.getpass("MySQL root password for test cleanup (leave blank if none): ")


def main():
    print("PHASE 7G — COMMIT HISTORY AND TREE/BLOB BROWSING API TEST")
    print(f"Backend: {BASE_URL}")
    if not login("postmanA") or not login("postmanB"):
        return 1
    run_public_checks("postmanA", "postmanB")
    run_private_checks("postmanA", "postmanB")
    db_password = configured_database_password()
    cleanup_ok = cleanup(db_password)
    if not cleanup_ok:
        check("All test data cleaned up", False)
    print("\n" + "=" * 60)
    print(f"PASSED: {passed}")
    print(f"FAILED: {failed}")
    print(f"TOTAL : {passed + failed}")
    print("ALL PHASE 7G API TESTS PASSED" if failed == 0 else "SOME PHASE 7G API TESTS FAILED")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
