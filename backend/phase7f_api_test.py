import base64
import getpass
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import uuid
from pathlib import Path

import requests


BASE_URL = os.environ.get("VERSION_CONTROL_API_URL", "http://localhost:8080")
STORAGE_ROOT = Path(os.environ.get("VERSION_CONTROL_STORAGE_ROOT", r"C:\Users\hakim\OneDrive\Desktop\version-control-storage"))
MYSQL_DB = os.environ.get("MYSQL_DATABASE", "version_control")
MYSQL_USER = os.environ.get("MYSQL_USER", "root")
MYSQL_EXE = os.environ.get("MYSQL_EXE") or shutil.which("mysql") or r"C:\Users\hakim\OneDrive\Desktop\MySQL\MySQL Server 8.0\bin\mysql.exe"
TIMEOUT = 20

session = requests.Session()
passed = 0
failed = 0
repository_ids = []
commit_hashes = []
tree_hashes = set()
blob_hashes = set()


def test(name, condition, response=None):
    global passed, failed
    if condition:
        passed += 1
        print(f"[PASS] {name}")
    else:
        failed += 1
        print(f"[FAIL] {name}")
        if response is not None:
            print(f"       HTTP {response.status_code}: {response.text[:400]}")


def data(response):
    try:
        return response.json()
    except (AttributeError, ValueError):
        return {}


def api(method, path, token=None, **kwargs):
    headers = dict(kwargs.pop("headers", {}))
    if token:
        headers["Authorization"] = f"Bearer {token}"
    try:
        return session.request(method, BASE_URL + path, headers=headers, timeout=TIMEOUT, **kwargs)
    except requests.RequestException as error:
        print(f"[REQUEST ERROR] {method} {path}: {error}")
        return None


def record_commit(response):
    payload = data(response)
    if payload.get("commitHash"):
        commit_hashes.append(payload["commitHash"])
    if payload.get("rootTreeHash"):
        tree_hashes.add(payload["rootTreeHash"])
    return payload


def tree_hash(entries):
    encoded = bytearray(b"VC-TREE\0v1")
    ordered = sorted(entries, key=lambda item: item[0].encode("utf-8"))
    encoded.extend(struct.pack(">I", len(ordered)))
    for name, entry_type, object_hash in ordered:
        name_bytes = name.encode("utf-8")
        encoded.append(entry_type)
        encoded.extend(struct.pack(">I", len(name_bytes)))
        encoded.extend(name_bytes)
        encoded.extend(bytes.fromhex(object_hash))
    return hashlib.sha256(encoded).hexdigest()


def create_repository(token, prefix, visibility="PUBLIC"):
    response = api("POST", "/api/repositories", token, json={
        "name": f"phase7f-{prefix}-{uuid.uuid4().hex[:12]}",
        "description": "Temporary Phase 7F API test repository",
        "visibility": visibility,
    })
    payload = data(response)
    if response is not None and response.status_code == 201 and payload.get("id"):
        repository_ids.append(int(payload["id"]))
    return response, payload


def run_mysql(sql, password):
    env = os.environ.copy()
    env["MYSQL_PWD"] = password
    try:
        return subprocess.run(
            [MYSQL_EXE, "--batch", "--skip-column-names", "--default-character-set=utf8mb4",
             "-u", MYSQL_USER, MYSQL_DB, "-e", sql],
            env=env, capture_output=True, text=True, timeout=25, check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        print(f"[MYSQL ERROR] {error}")
        return None


def cleanup(mysql_password, token):
    global failed

    if not repository_ids:
        test("No test repositories created; nothing to clean", True)
        return

    ids = ",".join(str(value) for value in repository_ids)

    # ------------------------------------------------------------
    # 1. Capture all trees belonging to test repositories.
    # ------------------------------------------------------------

    tree_query = f"""
WITH RECURSIVE test_trees(id) AS (
    SELECT root_tree_id
    FROM commits
    WHERE repository_id IN ({ids})

    UNION DISTINCT

    SELECT te.child_tree_id
    FROM tree_entries te
    JOIN test_trees tt
      ON te.tree_id = tt.id
    WHERE te.child_tree_id IS NOT NULL
)
SELECT id
FROM test_trees;
"""

    tree_result = run_mysql(tree_query, mysql_password)

    if tree_result is None or tree_result.returncode != 0:
        test("Find test trees for cleanup", False)
        if tree_result is not None:
            print(f"[CLEANUP ERROR] {tree_result.stderr.strip()}")
        return

    test_tree_ids = [
        int(line.strip())
        for line in tree_result.stdout.splitlines()
        if line.strip()
    ]

    # ------------------------------------------------------------
    # 2. Capture blobs referenced by those trees.
    # ------------------------------------------------------------

    test_tree_id_sql = ",".join(str(value) for value in test_tree_ids)

    test_blob_ids = []

    if test_tree_ids:
        blob_query = f"""
SELECT DISTINCT blob_id
FROM tree_entries
WHERE tree_id IN ({test_tree_id_sql})
  AND blob_id IS NOT NULL;
"""

        blob_result = run_mysql(blob_query, mysql_password)

        if blob_result is None or blob_result.returncode != 0:
            test("Find test blobs for cleanup", False)
            if blob_result is not None:
                print(f"[CLEANUP ERROR] {blob_result.stderr.strip()}")
            return

        test_blob_ids = [
            int(line.strip())
            for line in blob_result.stdout.splitlines()
            if line.strip()
        ]

    # ------------------------------------------------------------
    # 3. Delete branches first.
    # ------------------------------------------------------------

    branch_delete = run_mysql(
        f"DELETE FROM branches WHERE repository_id IN ({ids});",
        mysql_password,
    )

    if branch_delete is None or branch_delete.returncode != 0:
        test("Delete test branches", False)
        if branch_delete is not None:
            print(f"[CLEANUP ERROR] {branch_delete.stderr.strip()}")
        return

    # ------------------------------------------------------------
    # 4. Find commits in CHILD -> PARENT order.
    # ------------------------------------------------------------

    commit_query = f"""
WITH RECURSIVE commit_chain AS (
    SELECT
        id,
        repository_id,
        parent_commit_id,
        0 AS depth
    FROM commits
    WHERE repository_id IN ({ids})
      AND parent_commit_id IS NULL

    UNION ALL

    SELECT
        c.id,
        c.repository_id,
        c.parent_commit_id,
        cc.depth + 1
    FROM commits c
    JOIN commit_chain cc
      ON c.repository_id = cc.repository_id
     AND c.parent_commit_id = cc.id
)
SELECT id
FROM commit_chain
ORDER BY depth DESC, id DESC;
"""

    commit_result = run_mysql(commit_query, mysql_password)

    if commit_result is None or commit_result.returncode != 0:
        test("Find test commits in child-to-parent order", False)
        if commit_result is not None:
            print(f"[CLEANUP ERROR] {commit_result.stderr.strip()}")
        return

    commit_ids = [
        int(line.strip())
        for line in commit_result.stdout.splitlines()
        if line.strip()
    ]

    # ------------------------------------------------------------
    # 5. Delete commits one at a time: CHILD -> PARENT.
    # ------------------------------------------------------------

    for commit_id in commit_ids:
        delete_result = run_mysql(
            f"DELETE FROM commits WHERE id = {commit_id};",
            mysql_password,
        )

        if delete_result is None or delete_result.returncode != 0:
            test("Delete test commits child-before-parent", False)

            if delete_result is not None:
                print(
                    f"[CLEANUP ERROR] Failed deleting commit "
                    f"{commit_id}: {delete_result.stderr.strip()}"
                )

            return

    # ------------------------------------------------------------
    # 6. Find trees still reachable from remaining commits.
    # ------------------------------------------------------------

    live_tree_query = """
WITH RECURSIVE live_trees(id) AS (
    SELECT root_tree_id
    FROM commits

    UNION DISTINCT

    SELECT te.child_tree_id
    FROM tree_entries te
    JOIN live_trees lt
      ON te.tree_id = lt.id
    WHERE te.child_tree_id IS NOT NULL
)
SELECT id
FROM live_trees;
"""

    live_tree_result = run_mysql(
        live_tree_query,
        mysql_password,
    )

    if live_tree_result is None or live_tree_result.returncode != 0:
        test("Find remaining live trees", False)

        if live_tree_result is not None:
            print(f"[CLEANUP ERROR] {live_tree_result.stderr.strip()}")

        return

    live_tree_ids = {
        int(line.strip())
        for line in live_tree_result.stdout.splitlines()
        if line.strip()
    }

    # Only delete trees that belonged to our test repository
    # AND are no longer reachable from any remaining commit.
    unreachable_tree_ids = [
        tree_id
        for tree_id in test_tree_ids
        if tree_id not in live_tree_ids
    ]

    # ------------------------------------------------------------
    # 7. Delete tree entries first.
    # ------------------------------------------------------------

    if unreachable_tree_ids:
        unreachable_tree_sql = ",".join(
            str(value) for value in unreachable_tree_ids
        )

        delete_entries = run_mysql(
            f"""
DELETE FROM tree_entries
WHERE tree_id IN ({unreachable_tree_sql});
""",
            mysql_password,
        )

        if delete_entries is None or delete_entries.returncode != 0:
            test("Delete unreachable test tree entries", False)

            if delete_entries is not None:
                print(
                    f"[CLEANUP ERROR] {delete_entries.stderr.strip()}"
                )

            return

        # --------------------------------------------------------
        # 8. Delete unreachable trees.
        # --------------------------------------------------------

        delete_trees = run_mysql(
            f"""
DELETE FROM trees
WHERE id IN ({unreachable_tree_sql})
  AND NOT EXISTS (
      SELECT 1
      FROM tree_entries te
      WHERE te.child_tree_id = trees.id
  );
""",
            mysql_password,
        )

        if delete_trees is None or delete_trees.returncode != 0:
            test("Delete unreachable test trees", False)

            if delete_trees is not None:
                print(
                    f"[CLEANUP ERROR] {delete_trees.stderr.strip()}"
                )

            return

    # ------------------------------------------------------------
    # 9. Delete blobs that are no longer referenced.
    # ------------------------------------------------------------

    if test_blob_ids:
        blob_ids_sql = ",".join(
            str(value) for value in test_blob_ids
        )

        delete_blobs = run_mysql(
            f"""
DELETE FROM blobs
WHERE id IN ({blob_ids_sql})
  AND NOT EXISTS (
      SELECT 1
      FROM tree_entries te
      WHERE te.blob_id = blobs.id
  );
""",
            mysql_password,
        )

        if delete_blobs is None or delete_blobs.returncode != 0:
            test("Delete unreferenced test blobs", False)

            if delete_blobs is not None:
                print(
                    f"[CLEANUP ERROR] {delete_blobs.stderr.strip()}"
                )

            return

    test(
        "Clean test-only branches, commits, unreachable trees and unreferenced blobs",
        True,
    )

    # ------------------------------------------------------------
    # 10. Finally delete repositories through the API.
    # ------------------------------------------------------------

    for repository_id in repository_ids:
        response = api(
            "DELETE",
            f"/api/repositories/{repository_id}",
            token,
        )

        test(
            f"Delete temporary repository {repository_id}",
            response is not None and response.status_code == 204,
            response,
        )


print("=" * 72)
print("PHASE 7F — COMMIT ENGINE HTTP API TEST")
print(f"Backend: {BASE_URL}")
password_a = getpass.getpass("Password for postmanA: ")
password_b = getpass.getpass("Password for postmanB: ")
login_a = api("POST", "/api/auth/login", json={"identifier": "postmanA", "password": password_a})
login_b = api("POST", "/api/auth/login", json={"identifier": "postmanB", "password": password_b})
token_a = data(login_a).get("accessToken")
token_b = data(login_b).get("accessToken")
test("Login postmanA", login_a is not None and login_a.status_code == 200 and bool(token_a), login_a)
test("Login postmanB", login_b is not None and login_b.status_code == 200 and bool(token_b), login_b)

if token_a and token_b:
    try:
        suffix = uuid.uuid4().hex[:10]
        created, repo = create_repository(token_a, "main-" + suffix)
        repository_id = repo.get("id")
        test("Create unique public test repository", created is not None and created.status_code == 201 and bool(repository_id), created)
        if repository_id:
            init = api("POST", f"/api/repositories/{repository_id}/git/init", token_a)
            initialized = record_commit(init)
            test("Initialize repository", init is not None and init.status_code == 201, init)
            initial_hash = initialized.get("commitHash")
            initial_tree = initialized.get("rootTreeHash")
            if initial_tree:
                tree_hashes.add(initial_tree)
            branches = api("GET", f"/api/repositories/{repository_id}/branches")
            main = next((b for b in data(branches) if b.get("name") == "main"), {}) if branches else {}
            current_head = main.get("headCommit", {}).get("commitHash")
            test("Read initial main HEAD", branches is not None and branches.status_code == 200 and current_head == initial_hash, branches)

            first_bytes = b"# Phase 7F\n"
            first_blob_hash = hashlib.sha256(first_bytes).hexdigest()
            blob_hashes.add(first_blob_hash)
            first = api("POST", f"/api/repositories/{repository_id}/commits", token_a, json={
                "branch": "main", "expectedHeadHash": current_head, "message": "Add README",
                "changes": [{"path": "README.md", "content": first_bytes.decode("utf-8")}], "deletedPaths": [],
            })
            first_commit = record_commit(first)
            expected_first_tree = tree_hash([("README.md", 1, first_blob_hash)])
            test("First commit returns 201 with parent and tree hash", first is not None and first.status_code == 201
                 and first_commit.get("parentCommitHash") == initial_hash
                 and first_commit.get("rootTreeHash") == expected_first_tree, first)
            first_hash = first_commit.get("commitHash")
            first_tree = first_commit.get("rootTreeHash")
            latest = api("GET", f"/api/repositories/{repository_id}/branches", token_b)
            current = next((b for b in data(latest) if b.get("name") == "main"), {}) if latest else {}
            current_head = current.get("headCommit", {}).get("commitHash")
            test("Main HEAD advances to first commit", latest is not None and latest.status_code == 200 and current_head == first_hash, latest)

            binary = bytes([0, 1, 2, 3, 254, 255])
            binary_hash = hashlib.sha256(binary).hexdigest()
            blob_hashes.add(binary_hash)
            updated_readme_hash = hashlib.sha256(b"# Updated\n").hexdigest()
            helper_hash = hashlib.sha256(b"helper").hexdigest()
            utils_tree = tree_hash([("helper.txt", 1, helper_hash)])
            src_tree = tree_hash([("main.bin", 1, binary_hash), ("utils", 2, utils_tree)])
            expected_second_tree = tree_hash([
                ("README.md", 1, updated_readme_hash), ("copy.bin", 1, binary_hash), ("src", 2, src_tree),
            ])
            second = api("POST", f"/api/repositories/{repository_id}/commits", token_a, json={
                "branch": "main", "expectedHeadHash": current_head, "message": "Replace and add nested files",
                "changes": [
                    {"path": "README.md", "content": "# Updated\n"},
                    {"path": "src/main.bin", "contentBase64": base64.b64encode(binary).decode("ascii")},
                    {"path": "copy.bin", "contentBase64": base64.b64encode(binary).decode("ascii")},
                    {"path": "src/utils/helper.txt", "content": "helper"},
                ], "deletedPaths": [],
            })
            second_commit = record_commit(second)
            test("Second commit parent points to first commit", second is not None and second.status_code == 201
                 and second_commit.get("parentCommitHash") == first_hash
                 and second_commit.get("rootTreeHash") == expected_second_tree, second)
            second_hash = second_commit.get("commitHash")
            second_tree = second_commit.get("rootTreeHash")
            head = api("GET", f"/api/repositories/{repository_id}/branches")
            branch = next((b for b in data(head) if b.get("name") == "main"), {}) if head else {}
            head_hash = branch.get("headCommit", {}).get("commitHash")
            test("HEAD advances after second commit", head is not None and head.status_code == 200 and head_hash == second_hash, head)

            deleted = api("POST", f"/api/repositories/{repository_id}/commits", token_a, json={
                "branch": "main", "expectedHeadHash": head_hash, "message": "Delete README",
                "changes": [], "deletedPaths": ["README.md"],
            })
            deleted_commit = record_commit(deleted)
            expected_deleted_tree = tree_hash([("copy.bin", 1, binary_hash), ("src", 2, src_tree)])
            test("Explicit file deletion creates a child commit", deleted is not None and deleted.status_code == 201
                 and deleted_commit.get("parentCommitHash") == second_hash
                 and deleted_commit.get("rootTreeHash") == expected_deleted_tree, deleted)

            stale = api("POST", f"/api/repositories/{repository_id}/commits", token_a, json={
                "branch": "main", "expectedHeadHash": initial_hash, "message": "Stale write",
                "changes": [{"path": "stale.txt", "content": "stale"}],
            })
            test("Stale expected HEAD returns 409", stale is not None and stale.status_code == 409, stale)
            noop = api("POST", f"/api/repositories/{repository_id}/commits", token_a, json={
                "branch": "main", "message": "No changes", "changes": [], "deletedPaths": [],
            })
            test("Empty commit returns 400", noop is not None and noop.status_code == 400, noop)
            replacement_noop = api("POST", f"/api/repositories/{repository_id}/commits", token_a, json={
                "branch": "main", "message": "Same bytes", "changes": [{"path": "copy.bin", "contentBase64": base64.b64encode(binary).decode("ascii")}],
            })
            test("Identical replacement returns 400", replacement_noop is not None and replacement_noop.status_code == 400, replacement_noop)
            conflict = api("POST", f"/api/repositories/{repository_id}/commits", token_a, json={
                "branch": "main", "message": "Conflicting path", "changes": [{"path": "copy.bin", "content": "new"}],
                "deletedPaths": ["copy.bin"],
            })
            test("Change and delete conflict returns 400", conflict is not None and conflict.status_code == 400, conflict)
            forbidden = api("POST", f"/api/repositories/{repository_id}/commits", token_b, json={
                "branch": "main", "message": "Non-owner", "changes": [{"path": "x", "content": "x"}],
            })
            anonymous = api("POST", f"/api/repositories/{repository_id}/commits", json={
                "branch": "main", "message": "Anonymous", "changes": [{"path": "x", "content": "x"}],
            })
            test("Non-owner commit returns 403", forbidden is not None and forbidden.status_code == 403, forbidden)
            test("Anonymous commit returns 401", anonymous is not None and anonymous.status_code == 401, anonymous)

            raw_path = STORAGE_ROOT / "objects" / binary_hash[:2] / binary_hash[2:]
            test("Binary bytes are stored unchanged via ObjectStorage", raw_path.is_file() and raw_path.read_bytes() == binary)
            mysql_password = getpass.getpass("MySQL password for test assertions and cleanup: ")
            blob_sql = "SELECT COUNT(*) FROM blobs WHERE object_hash='" + binary_hash + "';"
            blob_count = run_mysql(blob_sql, mysql_password)
            test("Identical content paths reuse one Blob row", blob_count is not None and blob_count.returncode == 0
                 and blob_count.stdout.strip() == "1")
            if blob_count is not None and blob_count.returncode != 0:
                print(f"[MYSQL ERROR] {blob_count.stderr.strip()}")
            cleanup(mysql_password, token_a)
    except Exception as error:
        failed += 1
        print(f"[UNEXPECTED ERROR] {type(error).__name__}: {error}")
        if repository_ids:
            cleanup_password = getpass.getpass("MySQL password to clean up test data: ")
            cleanup(cleanup_password, token_a)
else:
    failed += 1
    print("Cannot continue without postmanA and postmanB JWTs.")

print("=" * 72)
print(f"PASSED: {passed}")
print(f"FAILED: {failed}")
print(f"TOTAL: {passed + failed}")
if failed == 0:
    print("ALL PHASE 7F API TESTS PASSED")
else:
    print("PHASE 7F API TESTS FAILED")
sys.exit(0 if failed == 0 else 1)
