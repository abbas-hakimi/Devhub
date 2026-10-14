"""HTTP acceptance checks for Phase 8F pull request merge behavior."""
import getpass
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid

BASE_URL = os.environ.get("PHASE8F_BASE_URL", "http://localhost:8080")
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
        suffix = f" HTTP {response.status_code}: {response.text[:300]}" if response else ""
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


def cleanup(password):
    ok = True
    for repo_id in reversed(repositories):
        sql = f"""START TRANSACTION;
SET @phase8f_repo = {int(repo_id)};
DELETE FROM pull_request_review_comments WHERE repository_id = @phase8f_repo;
DELETE FROM pull_request_reviews WHERE repository_id = @phase8f_repo;
DELETE FROM pull_requests WHERE repository_id = @phase8f_repo;
DELETE FROM repository_collaborators WHERE repository_id = @phase8f_repo;
DELETE FROM branches WHERE repository_id = @phase8f_repo;
UPDATE commits SET parent_commit_id = NULL, second_parent_commit_id = NULL WHERE repository_id = @phase8f_repo;
DELETE FROM commits WHERE repository_id = @phase8f_repo;
DELETE FROM repositories WHERE id = @phase8f_repo;
COMMIT;"""
        result = mysql(password, sql)
        success = result.returncode == 0
        check(f"Cleanup repository {repo_id}", success)
        if not success:
            print(result.stderr[:500])
        ok = success and ok
    return ok


def repository(user, name):
    response = call("POST", "/api/repositories", user, {"name": name, "visibility": "PRIVATE"})
    check("Create private merge-test repository", response is not None and response.status_code == 201, response)
    repo_id = body(response).get("id")
    if repo_id:
        repositories.append(repo_id)
    return repo_id


def create_commit(user, repo_id, branch, head, message, changes):
    response = call("POST", f"/api/repositories/{repo_id}/commits", user, {
        "branch": branch, "expectedHeadHash": head, "message": message, "changes": changes,
    })
    check(f"Commit on {branch}: {message}", response is not None and response.status_code == 201, response)
    return body(response).get("commitHash")


def head(repo_id, branch_name):
    response = call("GET", f"/api/repositories/{repo_id}/branches", "postmanA")
    branches = body(response) if response else []
    for branch in branches:
        if branch.get("name") == branch_name:
            return branch.get("headCommit", {}).get("commitHash")
    return None


def create_pr(user, repo_id, source):
    response = call("POST", f"/api/repositories/{repo_id}/pull-requests", user, {
        "title": "Phase 8F merge test", "sourceBranch": source, "targetBranch": "main",
    })
    check("Create pull request " + source, response is not None and response.status_code == 201, response)
    return body(response).get("id")


def approve(pr_id):
    response = call("POST", f"/api/pull-requests/{pr_id}/reviews", "postmanA",
                    {"state": "APPROVED", "body": "Approved for merge"})
    check("Owner approves collaborator PR", response is not None and response.status_code == 201, response)


def merge_payload(strategy, source, target, message):
    return {"strategy": strategy, "expectedSourceHead": source,
            "expectedTargetHead": target, "commitMessage": message}


print("Phase 8F pull request merge API tests")
if not login("postmanA") or not login("postmanB"):
    print("Login failed; tests cannot continue.")
    raise SystemExit(1)

run_id = uuid.uuid4().hex[:10]
repo_id = repository("postmanA", "phase8f-" + run_id)
if repo_id:
    initialized = call("POST", f"/api/repositories/{repo_id}/git/init", "postmanA")
    check("Initialize test repository", initialized is not None and initialized.status_code == 201, initialized)
    main_head = body(initialized).get("commitHash")
    invite = call("POST", f"/api/repositories/{repo_id}/collaborators/invitations", "postmanA",
                  {"username": "postmanB", "role": "WRITE"})
    check("Invite WRITE collaborator", invite is not None and invite.status_code == 201, invite)
    invitation_id = body(invite).get("invitationId")
    accepted = call("POST", f"/api/collaboration-invitations/{invitation_id}/accept", "postmanB") if invitation_id else None
    check("WRITE collaborator accepts", accepted is not None and accepted.status_code == 200, accepted)

    if main_head:
        seed = create_commit("postmanA", repo_id, "main", main_head, "Seed shared text", [
            {"path": "shared.txt", "content": "one\ntwo\nthree\n"},
        ])
        branch = call("POST", f"/api/repositories/{repo_id}/branches", "postmanB",
                      {"name": "feature/fast-forward", "fromCommitHash": seed})
        check("WRITE collaborator creates fast-forward branch", branch is not None and branch.status_code == 201, branch)
        feature = create_commit("postmanB", repo_id, "feature/fast-forward", seed, "Fast-forward feature", [
            {"path": "fast.txt", "content": "fast-forward"},
        ]) if seed else None
        pr = create_pr("postmanB", repo_id, "feature/fast-forward") if feature else None
        if pr:
            approve(pr)
            wrong = call("POST", f"/api/pull-requests/{pr}/merge", "postmanB",
                         merge_payload("FAST_FORWARD", feature, seed, "Unauthorized"))
            check("WRITE collaborator cannot merge", wrong is not None and wrong.status_code == 403, wrong)
            stale = call("POST", f"/api/pull-requests/{pr}/merge", "postmanA",
                         merge_payload("FAST_FORWARD", "0" * 64, seed, "Stale heads"))
            check("Stale expected HEAD is rejected", stale is not None and stale.status_code == 409, stale)
            merged = call("POST", f"/api/pull-requests/{pr}/merge", "postmanA",
                          merge_payload("FAST_FORWARD", feature, seed, "Fast-forward"))
            check("Owner fast-forward merges PR", merged is not None and merged.status_code == 200
                  and body(merged).get("state") == "MERGED", merged)
            check("Fast-forward updates target HEAD", head(repo_id, "main") == feature)
            repeated = call("POST", f"/api/pull-requests/{pr}/merge", "postmanA",
                            merge_payload("FAST_FORWARD", feature, seed, "Repeat"))
            check("Repeated merge is rejected", repeated is not None and repeated.status_code == 409, repeated)

        current = head(repo_id, "main")
        diverged = call("POST", f"/api/repositories/{repo_id}/branches", "postmanB",
                        {"name": "feature/diverged", "fromCommitHash": current})
        check("Create divergent feature branch", diverged is not None and diverged.status_code == 201, diverged)
        source = create_commit("postmanB", repo_id, "feature/diverged", current, "Feature file", [
            {"path": "feature.txt", "content": "feature"},
        ]) if current else None
        target = create_commit("postmanA", repo_id, "main", current, "Target file", [
            {"path": "target.txt", "content": "target"},
        ]) if current else None
        pr2 = create_pr("postmanB", repo_id, "feature/diverged") if source and target else None
        if pr2:
            approve(pr2)
            mergeability = call("GET", f"/api/pull-requests/{pr2}/mergeability", "postmanB")
            check("Mergeability reports clean divergence", mergeability is not None and mergeability.status_code == 200
                  and body(mergeability).get("mergeable") is True and body(mergeability).get("fastForwardEligible") is False
                  and body(mergeability).get("aheadBy") == 1 and body(mergeability).get("behindBy") == 1, mergeability)
            merged2 = call("POST", f"/api/pull-requests/{pr2}/merge", "postmanA",
                           merge_payload("MERGE_COMMIT", source, target, "Merge independent feature"))
            merge_hash = body(merged2).get("mergeCommitHash")
            check("Owner creates two-parent merge commit", merged2 is not None and merged2.status_code == 200
                  and body(merged2).get("state") == "MERGED" and merge_hash, merged2)
            check("Merge commit advances target", head(repo_id, "main") == merge_hash)

        conflict_base = head(repo_id, "main")
        conflict_branch = call("POST", f"/api/repositories/{repo_id}/branches", "postmanB",
                               {"name": "feature/conflict", "fromCommitHash": conflict_base})
        check("Create conflict branch", conflict_branch is not None and conflict_branch.status_code == 201, conflict_branch)
        conflict_source = create_commit("postmanB", repo_id, "feature/conflict", conflict_base, "Conflict source", [
            {"path": "shared.txt", "content": "one\nsource\nthree\n"},
            {"path": "node", "content": "file"},
        ]) if conflict_base else None
        conflict_target = create_commit("postmanA", repo_id, "main", conflict_base, "Conflict target", [
            {"path": "shared.txt", "content": "one\ntarget\nthree\n"},
            {"path": "node/child.txt", "content": "directory"},
        ]) if conflict_base else None
        pr3 = create_pr("postmanB", repo_id, "feature/conflict") if conflict_source and conflict_target else None
        if pr3:
            approve(pr3)
            before = head(repo_id, "main")
            state = call("GET", f"/api/pull-requests/{pr3}/mergeability", "postmanB")
            conflicts = body(state).get("conflictPaths", [])
            check("Mergeability returns text and file-directory conflicts", state is not None and state.status_code == 200
                  and body(state).get("mergeable") is False and "shared.txt" in conflicts
                  and "node" in conflicts and "node/child.txt" in conflicts, state)
            rejected = call("POST", f"/api/pull-requests/{pr3}/merge", "postmanA",
                            merge_payload("MERGE_COMMIT", conflict_source, conflict_target, "Conflicting merge"))
            check("Conflicts reject merge with paths", rejected is not None and rejected.status_code == 409
                  and body(rejected).get("conflictPaths"), rejected)
            check("Conflict leaves target HEAD and PR unchanged", head(repo_id, "main") == before
                  and body(call("GET", f"/api/pull-requests/{pr3}", "postmanA")).get("state") == "OPEN")

cleanup_password = getpass.getpass("MySQL cleanup password (leave blank if none): ")
if repositories and not cleanup(cleanup_password):
    failed += 1

print("\n" + "=" * 60)
print(f"PASSED: {passed}")
print(f"FAILED: {failed}")
print(f"TOTAL : {passed + failed}")
print("ALL PHASE 8F API TESTS PASSED" if failed == 0 else "SOME PHASE 8F API TESTS FAILED")
print("=" * 60)
raise SystemExit(0 if failed == 0 else 1)
