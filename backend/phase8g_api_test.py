"""Focused HTTP regression flow for Phase 8G hardening.

Requires the backend on localhost:8080 and the existing postmanA/postmanB test users.
It creates one private repository and removes its relational test data at the end.
"""
import getpass
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid

BASE_URL = os.environ.get("PHASE8G_BASE_URL", "http://localhost:8080")
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
SET @phase8g_repo = {int(repo_id)};
DELETE FROM pull_request_review_comments WHERE repository_id = @phase8g_repo;
DELETE FROM pull_request_reviews WHERE repository_id = @phase8g_repo;
DELETE FROM pull_requests WHERE repository_id = @phase8g_repo;
DELETE FROM repository_collaborators WHERE repository_id = @phase8g_repo;
DELETE FROM branches WHERE repository_id = @phase8g_repo;
UPDATE commits SET parent_commit_id = NULL, second_parent_commit_id = NULL WHERE repository_id = @phase8g_repo;
DELETE FROM commits WHERE repository_id = @phase8g_repo;
DELETE FROM repositories WHERE id = @phase8g_repo;
COMMIT;"""
        result = mysql(password, sql)
        success = result.returncode == 0
        check(f"Cleanup repository {repo_id}", success)
        if not success:
            print(result.stderr[:500])
        ok = success and ok
    return ok


def commit(user, repo_id, branch, head, path, content):
    response = call("POST", f"/api/repositories/{repo_id}/commits", user, {
        "branch": branch, "expectedHeadHash": head, "message": "Phase 8G API test",
        "changes": [{"path": path, "content": content}],
    })
    check(f"Commit {path} on {branch}", response is not None and response.status_code == 201, response)
    return body(response).get("commitHash")


def main():
    print("Phase 8G backend hardening API regression flow")
    if not login("postmanA") or not login("postmanB"):
        print("Login failed; tests cannot continue.")
        raise SystemExit(1)

    name = "phase8g-" + uuid.uuid4().hex[:12]
    created = call("POST", "/api/repositories", "postmanA", {"name": name, "visibility": "PRIVATE"})
    check("Create isolated private repository", created is not None and created.status_code == 201, created)
    repo_id = body(created).get("id")
    if not repo_id:
        raise SystemExit(1)
    repositories.append(repo_id)

    hidden = call("GET", f"/api/repositories/{repo_id}")
    check("Anonymous private repository access is hidden", hidden is not None and hidden.status_code == 404, hidden)
    initialized = call("POST", f"/api/repositories/{repo_id}/git/init", "postmanA")
    check("Initialize Git repository", initialized is not None and initialized.status_code == 201, initialized)
    main_head = body(initialized).get("commitHash")

    invitation = call("POST", f"/api/repositories/{repo_id}/collaborators/invitations", "postmanA",
                      {"username": "postmanB", "role": "WRITE"})
    check("Invite write collaborator", invitation is not None and invitation.status_code == 201, invitation)
    invitation_id = body(invitation).get("invitationId")
    accepted = call("POST", f"/api/collaboration-invitations/{invitation_id}/accept", "postmanB") if invitation_id else None
    check("Collaborator accepts invitation", accepted is not None and accepted.status_code == 200, accepted)

    if main_head:
        seeded = commit("postmanA", repo_id, "main", main_head, "README.md", "seed")
        branch = call("POST", f"/api/repositories/{repo_id}/branches", "postmanB",
                      {"name": "feature/phase8g", "fromCommitHash": seeded})
        check("Collaborator creates feature branch", branch is not None and branch.status_code == 201, branch)
        source = commit("postmanB", repo_id, "feature/phase8g", seeded, "feature.txt", "feature") if seeded else None
        pr = call("POST", f"/api/repositories/{repo_id}/pull-requests", "postmanB",
                  {"title": "Phase 8G workflow", "sourceBranch": "feature/phase8g", "targetBranch": "main"}) if source else None
        check("Collaborator opens pull request", pr is not None and pr.status_code == 201, pr)
        pr_id = body(pr).get("id")
        if pr_id:
            self_review = call("POST", f"/api/pull-requests/{pr_id}/reviews", "postmanB",
                               {"state": "APPROVED", "body": "self review"})
            check("PR author cannot approve own PR", self_review is not None and self_review.status_code == 403, self_review)
            approval = call("POST", f"/api/pull-requests/{pr_id}/reviews", "postmanA",
                            {"state": "APPROVED", "body": "Approved"})
            check("Owner approval is recorded", approval is not None and approval.status_code == 201, approval)
            merge = call("POST", f"/api/pull-requests/{pr_id}/merge", "postmanA", {
                "strategy": "FAST_FORWARD", "expectedSourceHead": source,
                "expectedTargetHead": seeded, "commitMessage": "Phase 8G fast-forward",
            })
            check("Approved pull request merges", merge is not None and merge.status_code == 200
                  and body(merge).get("state") == "MERGED", merge)
            repeated = call("POST", f"/api/pull-requests/{pr_id}/merge", "postmanA", {
                "strategy": "FAST_FORWARD", "expectedSourceHead": source,
                "expectedTargetHead": seeded, "commitMessage": "Repeat",
            })
            check("Repeated merge is rejected as conflict", repeated is not None and repeated.status_code == 409, repeated)

        downgrade = call("PUT", f"/api/repositories/{repo_id}/collaborators/{body(accepted).get('userId')}/role",
                         "postmanA", {"role": "READ"})
        check("Owner downgrades collaborator", downgrade is not None and downgrade.status_code == 200, downgrade)
        denied = call("POST", f"/api/repositories/{repo_id}/commits", "postmanB", {
            "branch": "feature/phase8g", "expectedHeadHash": source, "message": "must be denied",
            "changes": [{"path": "denied.txt", "content": "denied"}],
        })
        check("Role downgrade immediately revokes write access", denied is not None and denied.status_code == 403, denied)

        history = call("GET", f"/api/repositories/{repo_id}/commits?branch=main", "postmanA")
        check("Owner can page commit history", history is not None and history.status_code == 200
              and body(history).get("commits"), history)
        private_history = call("GET", f"/api/repositories/{repo_id}/commits?branch=main")
        check("Anonymous private history is hidden", private_history is not None and private_history.status_code == 404,
              private_history)

        second_branch = call("POST", f"/api/repositories/{repo_id}/branches", "postmanA",
                             {"name": "feature/closed", "fromCommitHash": source})
        check("Owner creates lifecycle test branch", second_branch is not None and second_branch.status_code == 201, second_branch)
        closed_source = commit("postmanA", repo_id, "feature/closed", source, "closed.txt", "closed")
        closed_pr = call("POST", f"/api/repositories/{repo_id}/pull-requests", "postmanA",
                         {"title": "Closed workflow", "sourceBranch": "feature/closed", "targetBranch": "main"})
        check("Create lifecycle test PR", closed_pr is not None and closed_pr.status_code == 201, closed_pr)
        closed_id = body(closed_pr).get("id")
        if closed_id:
            closed = call("POST", f"/api/pull-requests/{closed_id}/close", "postmanA")
            check("Open PR transitions to closed", closed is not None and closed.status_code == 200
                  and body(closed).get("state") == "CLOSED", closed)
            blocked = call("POST", f"/api/pull-requests/{closed_id}/merge", "postmanA", {
                "strategy": "FAST_FORWARD", "expectedSourceHead": closed_source,
                "expectedTargetHead": source, "commitMessage": "closed merge",
            })
            check("Closed PR cannot merge", blocked is not None and blocked.status_code == 409, blocked)

    cleanup_password = getpass.getpass("MySQL cleanup password (leave blank if none): ")
    cleanup(cleanup_password)
    print("\n" + "=" * 56)
    print(f"PASSED: {passed}\nFAILED: {failed}\nTOTAL : {passed + failed}")
    print("ALL PHASE 8G API TESTS PASSED" if failed == 0 else "SOME PHASE 8G API TESTS FAILED")
    print("=" * 56)
    raise SystemExit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
