import getpass
import os
import shutil
import subprocess
import sys
import uuid

import requests


BASE_URL = os.environ.get("VERSION_CONTROL_API_URL", "http://localhost:8080")
TIMEOUT = 15
MYSQL_DB = os.environ.get("MYSQL_DATABASE", "version_control")
MYSQL_USER = os.environ.get("MYSQL_USER", "root")
MYSQL_EXE = os.environ.get("MYSQL_EXE") or shutil.which("mysql") or r"C:\Users\hakim\OneDrive\Desktop\MySQL\MySQL Server 8.0\bin\mysql.exe"

session = requests.Session()
passed = 0
failed = 0
repositories = []
root_tree_hashes = set()


def test(name, condition, response=None):
    global passed, failed
    if condition:
        passed += 1
        print(f"[PASS] {name}")
    else:
        failed += 1
        print(f"[FAIL] {name}")
        if response is not None:
            print(f"       HTTP {response.status_code}: {response.text[:500]}")


def body(response):
    try:
        return response.json()
    except (ValueError, AttributeError):
        return {}


def request(method, path, token=None, **kwargs):
    headers = kwargs.pop("headers", {})
    if token:
        headers = {"Authorization": f"Bearer {token}", **headers}
    try:
        return session.request(method, BASE_URL + path, headers=headers, timeout=TIMEOUT, **kwargs)
    except requests.RequestException as error:
        print(f"[REQUEST ERROR] {method} {path}: {error}")
        return None


def login(identifier, password):
    response = request("POST", "/api/auth/login", json={"identifier": identifier, "password": password})
    token = body(response).get("accessToken") if response else None
    test(f"Login {identifier}", response is not None and response.status_code == 200 and bool(token), response)
    return token


def create_repository(token, suffix):
    response = request("POST", "/api/repositories", token, json={
        "name": f"phase7e-{suffix}-{uuid.uuid4().hex[:10]}",
        "description": "Phase 7E branch management API test",
        "visibility": "PUBLIC",
    })
    data = body(response)
    repository_id = data.get("id")
    if response is not None and response.status_code == 201 and repository_id:
        repositories.append(int(repository_id))
    return response, data


def initialize(token, repository_id):
    response = request("POST", f"/api/repositories/{repository_id}/git/init", token)
    data = body(response)
    if data.get("rootTreeHash"):
        root_tree_hashes.add(data["rootTreeHash"])
    return response, data


def create_branch(token, repository_id, name, from_hash=None):
    data = {"name": name}
    if from_hash is not None:
        data["fromCommitHash"] = from_hash
    return request("POST", f"/api/repositories/{repository_id}/branches", token, json=data)


def cleanup(mysql_password):
    global failed
    if not repositories:
        test("No test repositories required cleanup", True)
        return
    repository_ids = ",".join(str(value) for value in repositories)
    hashes = ",".join("'" + value + "'" for value in sorted(root_tree_hashes)) or "'__no_phase7e_tree_hash__'"
    sql = f"""
DELETE FROM branches WHERE repository_id IN ({repository_ids});
DELETE FROM commits WHERE repository_id IN ({repository_ids});
DELETE FROM trees
WHERE tree_hash IN ({hashes})
  AND NOT EXISTS (SELECT 1 FROM commits c WHERE c.root_tree_id = trees.id)
  AND NOT EXISTS (SELECT 1 FROM tree_entries te WHERE te.tree_id = trees.id);
"""
    env = os.environ.copy()
    env["MYSQL_PWD"] = mysql_password
    try:
        result = subprocess.run(
            [MYSQL_EXE, "--batch", "--skip-column-names", "--default-character-set=utf8mb4",
             "-u", MYSQL_USER, MYSQL_DB, "-e", sql],
            capture_output=True, text=True, timeout=20, env=env, check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        result = None
        print(f"[CLEANUP ERROR] Could not run MySQL cleanup: {error}")
    finally:
        env.pop("MYSQL_PWD", None)
    sql_ok = result is not None and result.returncode == 0
    if not sql_ok and result is not None:
        print(f"[CLEANUP ERROR] MySQL cleanup failed: {result.stderr.strip()}")
    test("Remove only test branch/commit rows and unreferenced empty trees", sql_ok)
    if not sql_ok:
        return

    for repository_id in repositories:
        response = request("DELETE", f"/api/repositories/{repository_id}", token_a)
        test(f"Delete test repository {repository_id}", response is not None and response.status_code == 204, response)


print("=" * 72)
print("PHASE 7E — GIT BRANCH MANAGEMENT API TEST")
print(f"Backend: {BASE_URL}")
password_a = getpass.getpass("Password for postmanA: ")
password_b = getpass.getpass("Password for postmanB: ")
token_a = login("postmanA", password_a)
token_b = login("postmanB", password_b)

if token_a and token_b:
    try:
        suffix = uuid.uuid4().hex[:12]
        setup_a, repo_a_data = create_repository(token_a, "branches-a-" + suffix)
        repo_a = repo_a_data.get("id")
        test("Create owner A test repository", setup_a is not None and setup_a.status_code == 201 and bool(repo_a), setup_a)

        setup_b, repo_b_data = create_repository(token_a, "branches-b-" + suffix)
        repo_b = repo_b_data.get("id")
        test("Create cross-repository test repository", setup_b is not None and setup_b.status_code == 201 and bool(repo_b), setup_b)

        if repo_a and repo_b:
            init_a, main_a = initialize(token_a, repo_a)
            init_b, main_b = initialize(token_a, repo_b)
            test("Initialize repository A", init_a is not None and init_a.status_code == 201, init_a)
            test("Initialize repository B", init_b is not None and init_b.status_code == 201, init_b)
            main_hash = main_a.get("commitHash")
            other_hash = main_b.get("commitHash")

            feature = create_branch(token_a, repo_a, "feature/phase7e")
            feature_data = body(feature)
            test("Create main-derived feature branch", feature is not None and feature.status_code == 201, feature)
            test("Feature HEAD equals main HEAD", feature_data.get("headCommit", {}).get("commitHash") == main_hash, feature)

            duplicate = create_branch(token_a, repo_a, "feature/phase7e")
            test("Duplicate branch returns 409", duplicate is not None and duplicate.status_code == 409, duplicate)

            invalid_results = [create_branch(token_a, repo_a, name) for name in
                               ("HEAD", "bad name", "bad//name", "name.lock", "bad~name", "trailing.")]
            test("Invalid branch names return 400", all(r is not None and r.status_code == 400 for r in invalid_results),
                 next((r for r in invalid_results if r is None or r.status_code != 400), None))

            non_owner = create_branch(token_b, repo_a, "feature/non-owner")
            anonymous = request("POST", f"/api/repositories/{repo_a}/branches", json={"name": "feature/anonymous"})
            test("Non-owner branch creation returns 403", non_owner is not None and non_owner.status_code == 403, non_owner)
            test("Anonymous branch creation returns 401", anonymous is not None and anonymous.status_code == 401, anonymous)

            listing = request("GET", f"/api/repositories/{repo_a}/branches")
            listing_data = body(listing) if listing else []
            main_branch = next((entry for entry in listing_data if entry.get("name") == "main"), {}) if isinstance(listing_data, list) else {}
            feature_branch = next((entry for entry in listing_data if entry.get("name") == "feature/phase7e"), {}) if isinstance(listing_data, list) else {}
            test("Anonymous branch listing includes main and feature", listing is not None and listing.status_code == 200
                 and bool(main_branch) and bool(feature_branch), listing)

            branch_id = feature_data.get("id")
            detail = request("GET", f"/api/branches/{branch_id}") if branch_id else None
            test("Branch detail returns compact HEAD metadata", detail is not None and detail.status_code == 200
                 and body(detail).get("headCommit", {}).get("commitHash") == main_hash, detail)

            explicit = create_branch(token_a, repo_a, "feature/phase7e-explicit", main_hash)
            test("Explicit same-repository source commit is accepted", explicit is not None and explicit.status_code == 201
                 and body(explicit).get("headCommit", {}).get("commitHash") == main_hash, explicit)

            cross_repository = create_branch(token_a, repo_a, "feature/cross-repository", other_hash)
            test("Cross-repository source commit returns 404", cross_repository is not None and cross_repository.status_code == 404,
                 cross_repository)
    finally:
        cleanup_password = getpass.getpass("MySQL root password for test cleanup (blank if none): ")
        cleanup(cleanup_password)
else:
    failed += 1
    print("Cannot continue without both postmanA and postmanB JWTs.")

print("=" * 72)
print(f"PASSED: {passed}")
print(f"FAILED: {failed}")
print(f"TOTAL : {passed + failed}")
print("ALL PHASE 7E API TESTS PASSED" if failed == 0 else "SOME PHASE 7E API TESTS FAILED")
sys.exit(0 if failed == 0 else 1)
