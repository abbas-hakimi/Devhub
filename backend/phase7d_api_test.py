import getpass

import sys

import requests
import os
import subprocess

from datetime import datetime

from concurrent.futures import ThreadPoolExecutor





BASE_URL = "http://localhost:8080"

TIMEOUT = 10

# Used only to clean test-created Git metadata after the API correctly returns 409.
MYSQL_EXE = r"C:\Users\hakim\OneDrive\Desktop\MySQL\MySQL Server 8.0\bin\mysql.exe"
MYSQL_DB = "version_control"
MYSQL_USER = "root"



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

    headers = kwargs.pop("headers", {})



    if token:

        headers = {

            "Authorization": f"Bearer {token}",

            "Content-Type": "application/json",

            **headers,

        }



    try:

        return s.request(

            method,

            BASE_URL + path,

            headers=headers,

            timeout=TIMEOUT,

            **kwargs,

        )

    except requests.RequestException as e:

        print(f"[REQUEST ERROR] {method} {path}: {e}")

        return None





def login(identifier, password, name):

    r = req(

        "POST",

        "/api/auth/login",

        json={

            "identifier": identifier,

            "password": password,

        },

    )



    d = j(r) if r else {}

    token = d.get("accessToken")



    test(

        name,

        r is not None

        and r.status_code == 200

        and bool(token),

        r,

    )



    return token





def create_repo(token, name, visibility):

    r = req(

        "POST",

        "/api/repositories",

        token=token,

        json={

            "name": name,

            "description": "Phase 7D automated test",

            "visibility": visibility,

        },

    )



    d = j(r) if r else {}

    rid = d.get("id")



    test(

        f"Create {visibility.lower()} repository",

        r is not None

        and r.status_code == 201

        and bool(rid),

        r,

    )



    return rid





def init_repo(token, repository_id):

    return req(

        "POST",

        f"/api/repositories/{repository_id}/git/init",

        token=token,

    )





print("=" * 70)

print("PHASE 7D — GIT REPOSITORY INITIALIZATION API TEST")

print("=" * 70)



print(f"\nBackend: {BASE_URL}\n")



password_a = getpass.getpass("Password for postmanA: ")

password_b = getpass.getpass("Password for postmanB: ")





# ----------------------------------------------------------------------

# AUTHENTICATION

# ----------------------------------------------------------------------



print("\n" + "-" * 70)

print("AUTHENTICATION")

print("-" * 70)



token_a = login(

    "postmanA",

    password_a,

    "User A login",

)



token_b = login(

    "postmanB",

    password_b,

    "User B login",

)



if not token_a or not token_b:

    print("Cannot continue without both JWT tokens.")

    sys.exit(1)





# ----------------------------------------------------------------------

# REPOSITORY SETUP

# ----------------------------------------------------------------------



run = datetime.now().strftime("%Y%m%d%H%M%S%f")



repo_id = None



print("\n" + "-" * 70)

print("REPOSITORY SETUP")

print("-" * 70)



repo_id = create_repo(

    token_a,

    f"phase7d-auto-{run}",

    "PUBLIC",

)



if not repo_id:

    print("Repository creation failed; stopping.")

    sys.exit(1)



print(f"\nRepository ID: {repo_id}")





# ----------------------------------------------------------------------

# FIRST INITIALIZATION

# ----------------------------------------------------------------------



print("\n" + "-" * 70)

print("FIRST INITIALIZATION")

print("-" * 70)



r = init_repo(token_a, repo_id)

d = j(r) if r else {}



test(

    "Owner initializes repository",

    r is not None and r.status_code == 201,

    r,

)



test(

    "Response repository ID is correct",

    str(d.get("repositoryId")) == str(repo_id),

    r,

)



test(

    "Branch name is main",

    d.get("branchName") == "main",

    r,

)



commit_hash = d.get("commitHash")

root_tree_hash = d.get("rootTreeHash")



test(

    "Initial commit hash returned",

    isinstance(commit_hash, str) and len(commit_hash) == 64,

    r,

)



test(

    "Initial root tree hash returned",

    isinstance(root_tree_hash, str) and len(root_tree_hash) == 64,

    r,

)



test(

    "Commit hash is lowercase hexadecimal",

    isinstance(commit_hash, str)

    and commit_hash == commit_hash.lower()

    and all(c in "0123456789abcdef" for c in commit_hash),

    r,

)



test(

    "Root tree hash is lowercase hexadecimal",

    isinstance(root_tree_hash, str)

    and root_tree_hash == root_tree_hash.lower()

    and all(c in "0123456789abcdef" for c in root_tree_hash),

    r,

)



print("\nInitial response:")

print(d)





# ----------------------------------------------------------------------

# REPEATED INITIALIZATION / IDEMPOTENCY

# ----------------------------------------------------------------------



print("\n" + "-" * 70)

print("REPEATED INITIALIZATION / IDEMPOTENCY")

print("-" * 70)



r2 = init_repo(token_a, repo_id)

d2 = j(r2) if r2 else {}



test(

    "Repeated initialization returns 200",

    r2 is not None and r2.status_code == 200,

    r2,

)



test(

    "Repeated response repository ID is correct",

    str(d2.get("repositoryId")) == str(repo_id),

    r2,

)



test(

    "Repeated response branch is main",

    d2.get("branchName") == "main",

    r2,

)



test(

    "Repeated initialization returns same commit hash",

    d2.get("commitHash") == commit_hash,

    r2,

)



test(

    "Repeated initialization returns same root tree hash",

    d2.get("rootTreeHash") == root_tree_hash,

    r2,

)





# ----------------------------------------------------------------------

# ANONYMOUS ACCESS

# ----------------------------------------------------------------------



print("\n" + "-" * 70)

print("ANONYMOUS ACCESS")

print("-" * 70)



r = init_repo(None, repo_id)



test(

    "Anonymous initialization rejected with 401",

    r is not None and r.status_code == 401,

    r,

)





# ----------------------------------------------------------------------

# NON-OWNER ACCESS

# ----------------------------------------------------------------------



print("\n" + "-" * 70)

print("NON-OWNER ACCESS")

print("-" * 70)



r = init_repo(token_b, repo_id)



test(

    "Non-owner initialization rejected with 403",

    r is not None and r.status_code == 403,

    r,

)





# ----------------------------------------------------------------------

# MISSING REPOSITORY

# ----------------------------------------------------------------------



print("\n" + "-" * 70)

print("MISSING REPOSITORY")

print("-" * 70)



missing_id = 999999999



r = init_repo(token_a, missing_id)



test(

    "Missing repository returns 404",

    r is not None and r.status_code == 404,

    r,

)





# ----------------------------------------------------------------------

# CONCURRENT INITIALIZATION

# ----------------------------------------------------------------------



print("\n" + "-" * 70)

print("CONCURRENT INITIALIZATION")

print("-" * 70)



concurrent_repo = create_repo(

    token_a,

    f"phase7d-concurrent-{run}",

    "PUBLIC",

)



if concurrent_repo:



    def concurrent_init():

        response = init_repo(token_a, concurrent_repo)

        return (

            response.status_code if response else None,

            j(response) if response else {},

        )



    with ThreadPoolExecutor(max_workers=2) as executor:

        futures = [

            executor.submit(concurrent_init),

            executor.submit(concurrent_init),

        ]



        results = [future.result() for future in futures]



    statuses = [status for status, _ in results]



    print(f"Concurrent HTTP statuses: {statuses}")



    test(

        "Concurrent requests both complete successfully",

        all(status in (200, 201) for status in statuses),

    )



    successful_bodies = [

        body for status, body in results

        if status in (200, 201)

    ]



    concurrent_hashes = {

        body.get("commitHash")

        for body in successful_bodies

        if body.get("commitHash")

    }



    concurrent_tree_hashes = {

        body.get("rootTreeHash")

        for body in successful_bodies

        if body.get("rootTreeHash")

    }



    test(

        "Concurrent initialization produces one commit hash",

        len(concurrent_hashes) == 1,

    )



    test(

        "Concurrent initialization produces one root tree hash",

        len(concurrent_tree_hashes) == 1,

    )



    test(

        "Concurrent initialization uses main branch",

        all(

            body.get("branchName") == "main"

            for body in successful_bodies

        ),

    )





# ----------------------------------------------------------------------
# CLEANUP
# ----------------------------------------------------------------------

print("\n" + "-" * 70)
print("CLEANUP")
print("-" * 70)

def mysql_cleanup(repository_id, root_tree_hash, label, db_password):
    """
    Test-only cleanup.

    The normal repository DELETE endpoint intentionally returns 409 while
    Git metadata exists. We therefore remove only the rows created by this
    test, in FK-safe order, and then call the normal repository DELETE API.

    Trees are content-addressed/shared, so a tree is deleted only when no
    commit references it anymore and it has no tree entries.
    Object files are intentionally left on disk; Phase 7 has no GC/delete
    operation for immutable storage objects.
    """
    if not repository_id:
        return True

    sql = f"""
DELETE FROM branches
WHERE repository_id = {int(repository_id)};

DELETE FROM commits
WHERE repository_id = {int(repository_id)};

DELETE FROM trees
WHERE tree_hash = {repr(root_tree_hash) if root_tree_hash else "NULL"}
  AND NOT EXISTS (
      SELECT 1
      FROM commits c
      WHERE c.root_tree_id = trees.id
  )
  AND NOT EXISTS (
      SELECT 1
      FROM tree_entries te
      WHERE te.tree_id = trees.id
  );
"""

    env = os.environ.copy()
    env["MYSQL_PWD"] = db_password

    try:
        result = subprocess.run(
            [
                MYSQL_EXE,
                "--batch",
                "--skip-column-names",
                "-u", MYSQL_USER,
                MYSQL_DB,
                "-e", sql,
            ],
            capture_output=True,
            text=True,
            timeout=15,
            env=env,
        )
    finally:
        # Avoid keeping the password in this Python process after mysql exits.
        env.pop("MYSQL_PWD", None)

    if result.returncode != 0:
        print(f"[CLEANUP ERROR] {label}: MySQL cleanup failed")
        print(result.stderr.strip())
        return False

    # Now the repository should have no Git metadata preventing deletion.
    r = req(
        "DELETE",
        f"/api/repositories/{repository_id}",
        token=token_a,
    )

    if r is None or r.status_code != 204:
        print(
            f"[CLEANUP ERROR] {label}: repository DELETE returned "
            f"{r.status_code if r else 'no response'}"
        )
        if r is not None:
            print(r.text[:600])
        return False

    print(f"[PASS] {label}")
    return True


cleanup_ok = True

db_password = getpass.getpass(
    "MySQL root password for test cleanup (leave blank if none): "
)

# Primary repository: the initial root tree hash is known from the first init.
cleanup_ok = mysql_cleanup(
    repo_id,
    root_tree_hash,
    "Delete primary test repository",
    db_password,
) and cleanup_ok

# Concurrent initialization should produce exactly one root tree hash.
concurrent_tree_hash = (
    next(iter(concurrent_tree_hashes))
    if concurrent_tree_hashes
    else None
)

cleanup_ok = mysql_cleanup(
    concurrent_repo,
    concurrent_tree_hash,
    "Delete concurrent test repository",
    db_password,
) and cleanup_ok

if not cleanup_ok:
    print("\n[CLEANUP FAILED] One or more test repositories could not be cleaned up.")
    failed += 1

# ----------------------------------------------------------------------

# FINAL RESULT

# ----------------------------------------------------------------------



print("\n" + "=" * 70)

print("FINAL RESULT")

print("=" * 70)



print(f"PASSED: {passed}")

print(f"FAILED: {failed}")

print(f"TOTAL : {passed + failed}")

print()



if failed == 0:

    print("ALL PHASE 7D API TESTS PASSED")

else:

    print("SOME PHASE 7D API TESTS FAILED")



print("=" * 70)



sys.exit(0 if failed == 0 else 1)
