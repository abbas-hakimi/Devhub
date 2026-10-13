"""HTTP acceptance checks for Phase 8E repository collaborators."""
import getpass
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid

BASE_URL = os.environ.get("PHASE8E_BASE_URL", "http://localhost:8080")
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


def repository(user, name, visibility):
    response = call("POST", "/api/repositories", user, {"name": name, "visibility": visibility})
    check("Create " + visibility.lower() + " repository", response is not None and response.status_code == 201, response)
    repo_id = body(response).get("id")
    if repo_id:
        repositories.append(repo_id)
    return repo_id


def create_commit(user, repo_id, branch, head, path, content):
    response = call("POST", f"/api/repositories/{repo_id}/commits", user, {
        "branch": branch, "expectedHeadHash": head, "message": "Phase 8E test commit",
        "changes": [{"path": path, "content": content}],
    })
    check("Create commit on " + branch, response is not None and response.status_code == 201, response)
    return body(response).get("commitHash")


def cleanup(password):
    # Scope all deletes to the unique repositories created by this script. Preserve
    # shared content-addressed trees/blobs because they may be used by other repos.
    ok = True
    for repo_id in reversed(repositories):
        sql = f"""START TRANSACTION;
SET @phase8e_repo = {int(repo_id)};
DELETE FROM pull_request_review_comments WHERE repository_id = @phase8e_repo;
DELETE FROM pull_request_reviews WHERE repository_id = @phase8e_repo;
DELETE FROM pull_requests WHERE repository_id = @phase8e_repo;
DELETE il FROM issue_labels il JOIN issues i ON i.id = il.issue_id WHERE i.repository_id = @phase8e_repo;
DELETE ic FROM issue_comments ic JOIN issues i ON i.id = ic.issue_id WHERE i.repository_id = @phase8e_repo;
DELETE FROM issues WHERE repository_id = @phase8e_repo;
DELETE FROM labels WHERE repository_id = @phase8e_repo;
DELETE FROM milestones WHERE repository_id = @phase8e_repo;
DELETE FROM branches WHERE repository_id = @phase8e_repo;
UPDATE commits SET parent_commit_id = NULL WHERE repository_id = @phase8e_repo;
DELETE FROM commits WHERE repository_id = @phase8e_repo;
DELETE FROM repository_collaborators WHERE repository_id = @phase8e_repo;
DELETE FROM repository_stars WHERE repository_id = @phase8e_repo;
DELETE FROM repositories WHERE id = @phase8e_repo;
COMMIT;"""
        result = mysql(password, sql)
        success = result.returncode == 0
        check(f"Cleanup repository {repo_id}", success)
        if not success:
            print(result.stderr[:500])
        ok = success and ok
    return ok


print("Phase 8E collaborator API tests")
if not login("postmanA") or not login("postmanB"):
    print("Login failed; tests cannot continue.")
    raise SystemExit(1)

run_id = uuid.uuid4().hex[:10]
repo_id = repository("postmanA", "phase8e-" + run_id, "PRIVATE")
if repo_id:
    init = call("POST", f"/api/repositories/{repo_id}/git/init", "postmanA")
    check("Owner initializes private repository", init is not None and init.status_code == 201, init)
    main_hash = body(init).get("commitHash")
    invite = call("POST", f"/api/repositories/{repo_id}/collaborators/invitations", "postmanA",
                  {"username": "postmanB", "role": "WRITE"})
    check("Owner invites postmanB as WRITE", invite is not None and invite.status_code == 201, invite)
    invitation_id = body(invite).get("invitationId")
    check("Pending invitee cannot read private repository", call("GET", f"/api/repositories/{repo_id}", "postmanB").status_code == 404)
    invitations = call("GET", "/api/me/collaboration-invitations", "postmanB")
    check("Invitee can see own pending invitation", invitations is not None and invitations.status_code == 200
          and any(item.get("invitationId") == invitation_id for item in body(invitations)))
    accepted = call("POST", f"/api/collaboration-invitations/{invitation_id}/accept", "postmanB")
    check("Invitee accepts invitation", accepted is not None and accepted.status_code == 200 and body(accepted).get("status") == "ACCEPTED", accepted)
    check("Accepted collaborator can browse private repository", call("GET", f"/api/repositories/{repo_id}", "postmanB").status_code == 200)

    branch = call("POST", f"/api/repositories/{repo_id}/branches", "postmanB",
                  {"name": "feature/phase8e", "fromCommitHash": main_hash})
    check("WRITE collaborator creates branch", branch is not None and branch.status_code == 201, branch)
    feature_head = create_commit("postmanB", repo_id, "feature/phase8e", main_hash, "feature.txt", "feature")
    create_commit("postmanA", repo_id, "main", main_hash, "base.txt", "base")
    pr = call("POST", f"/api/repositories/{repo_id}/pull-requests", "postmanB",
              {"title": "Phase 8E contributor PR", "sourceBranch": "feature/phase8e", "targetBranch": "main"})
    check("WRITE collaborator creates PR with own author identity", pr is not None and pr.status_code == 201
          and body(pr).get("author", {}).get("username") == "postmanB", pr)
    pr_id = body(pr).get("id")
    approval = call("POST", f"/api/pull-requests/{pr_id}/reviews", "postmanA", {"state": "APPROVED", "body": "Approved"})
    check("Owner approves collaborator-authored PR", approval is not None and approval.status_code == 201
          and body(approval).get("reviewer", {}).get("username") == "postmanA", approval)
    self_approval = call("POST", f"/api/pull-requests/{pr_id}/reviews", "postmanB", {"state": "APPROVED", "body": "Self"})
    check("PR author cannot approve own PR", self_approval is not None and self_approval.status_code == 403, self_approval)
    status = call("GET", f"/api/pull-requests/{pr_id}/review-status", "postmanB")
    check("Review status retains effective owner approval", status is not None and status.status_code == 200
          and body(status).get("currentApprovals") == 1, status)

    downgrade = call("PUT", f"/api/repositories/{repo_id}/collaborators/{body(accepted).get('userId')}/role",
                     "postmanA", {"role": "READ"})
    check("Owner downgrades collaborator to READ", downgrade is not None and downgrade.status_code == 200, downgrade)
    blocked = call("POST", f"/api/repositories/{repo_id}/branches", "postmanB",
                   {"name": "feature/blocked", "fromCommitHash": feature_head})
    check("READ collaborator cannot create branch", blocked is not None and blocked.status_code == 403, blocked)
    removed = call("DELETE", f"/api/repositories/{repo_id}/collaborators/{body(accepted).get('userId')}", "postmanA")
    check("Owner removes collaborator", removed is not None and removed.status_code == 204, removed)
    check("Removal revokes private repository access", call("GET", f"/api/repositories/{repo_id}", "postmanB").status_code == 404)

    other_repo = repository("postmanA", "phase8e-cross-" + run_id, "PRIVATE")
    if other_repo:
        cross = call("POST", f"/api/repositories/{other_repo}/branches", "postmanB",
                     {"name": "feature/cross", "fromCommitHash": main_hash})
        check("Collaborator role does not cross repository boundary", cross is not None and cross.status_code == 403, cross)

cleanup_password = getpass.getpass("MySQL cleanup password (leave blank if none): ")
if repositories and not cleanup(cleanup_password):
    failed += 1

print("\n" + "=" * 60)
print(f"PASSED: {passed}")
print(f"FAILED: {failed}")
print(f"TOTAL : {passed + failed}")
print("ALL PHASE 8E API TESTS PASSED" if failed == 0 else "SOME PHASE 8E API TESTS FAILED")
print("=" * 60)
raise SystemExit(0 if failed == 0 else 1)
