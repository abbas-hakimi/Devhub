"""HTTP acceptance checks for Phase 8D pull request reviews and comments."""
import getpass
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid

BASE_URL = os.environ.get("PHASE8D_BASE_URL", "http://localhost:8080")
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
CREATE TEMPORARY TABLE p8d_trees (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO p8d_trees WITH RECURSIVE walk(id) AS (
 SELECT root_tree_id FROM commits WHERE repository_id IN ({ids}) UNION DISTINCT
 SELECT e.child_tree_id FROM tree_entries e JOIN walk w ON e.tree_id=w.id WHERE e.child_tree_id IS NOT NULL
) SELECT id FROM walk;
CREATE TEMPORARY TABLE p8d_blobs (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO p8d_blobs SELECT DISTINCT e.blob_id FROM tree_entries e JOIN p8d_trees t ON t.id=e.tree_id
 JOIN blobs b ON b.id=e.blob_id WHERE e.blob_id IS NOT NULL AND b.id>{int(blob_floor)};
DELETE c FROM pull_request_review_comments c JOIN pull_requests p ON p.id=c.pull_request_id WHERE p.repository_id IN ({ids});
DELETE r FROM pull_request_reviews r JOIN pull_requests p ON p.id=r.pull_request_id WHERE p.repository_id IN ({ids});
DELETE FROM pull_requests WHERE repository_id IN ({ids});
DELETE FROM branches WHERE repository_id IN ({ids});
DELETE FROM commits WHERE repository_id IN ({ids}) ORDER BY id DESC;
CREATE TEMPORARY TABLE p8d_live (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO p8d_live WITH RECURSIVE walk(id) AS (
 SELECT root_tree_id FROM commits UNION DISTINCT
 SELECT e.child_tree_id FROM tree_entries e JOIN walk w ON e.tree_id=w.id WHERE e.child_tree_id IS NOT NULL
) SELECT id FROM walk;
DELETE e FROM tree_entries e JOIN p8d_trees t ON t.id=e.tree_id LEFT JOIN p8d_live l ON l.id=e.tree_id WHERE l.id IS NULL AND e.tree_id>{int(tree_floor)};
DELETE t FROM trees t JOIN p8d_trees c ON c.id=t.id LEFT JOIN p8d_live l ON l.id=t.id WHERE l.id IS NULL AND t.id>{int(tree_floor)};
DELETE b FROM blobs b JOIN p8d_blobs c ON c.id=b.id LEFT JOIN tree_entries e ON e.blob_id=b.id WHERE e.id IS NULL;
"""
    result = mysql(password, sql)
    if result.returncode:
        print("[CLEANUP ERROR] " + result.stderr.strip())
        return
    for repo, owner in repositories:
        response = call("DELETE", f"/api/repositories/{repo}", owner)
        check(f"Cleanup test repository {repo}", response is not None and response.status_code == 204, response)


def create_repository(user, visibility):
    payload = {"name": "phase8d-" + uuid.uuid4().hex[:14], "visibility": visibility,
               "description": "Phase 8D review API test"}
    response = call("POST", "/api/repositories", user, payload)
    repo_id = body(response).get("id")
    check(f"Create {visibility.lower()} review test repository", response is not None and response.status_code == 201 and repo_id, response)
    if repo_id:
        repositories.append((int(repo_id), user))
    return int(repo_id) if repo_id else None


def initialize_and_pr(user, repo_id, visibility):
    init = call("POST", f"/api/repositories/{repo_id}/git/init", user)
    initial = body(init).get("commitHash")
    check("Initialize review test repository", init is not None and init.status_code == 201 and initial, init)
    if not initial:
        return None
    branch = call("POST", f"/api/repositories/{repo_id}/branches", user,
                  {"name": "feature/reviews", "fromCommitHash": initial})
    check("Create review feature branch", branch is not None and branch.status_code == 201, branch)
    commit = call("POST", f"/api/repositories/{repo_id}/commits", user, {
        "branch": "feature/reviews", "expectedHeadHash": initial, "message": "Add reviewable file",
        "changes": [{"path": "src/Reviewable.java", "content": "first line\nsecond line\n"}],
    })
    feature = body(commit).get("commitHash")
    check("Create reviewable source commit", commit is not None and commit.status_code == 201 and feature, commit)
    if not feature:
        return None
    pr = call("POST", f"/api/repositories/{repo_id}/pull-requests", user, {
        "title": "Review API fixture", "sourceBranch": "feature/reviews", "targetBranch": "main"})
    pr_id = body(pr).get("id")
    check("Create review pull request", pr is not None and pr.status_code == 201 and pr_id, pr)
    return (int(pr_id), initial, feature) if pr_id else None


def run():
    repo = create_repository("postmanA", "PUBLIC")
    if repo:
        fixture = initialize_and_pr("postmanA", repo, "PUBLIC")
        if fixture:
            pr_id, initial, feature = fixture
            review_path = f"/api/pull-requests/{pr_id}/reviews"
            comment_path = f"/api/pull-requests/{pr_id}/comments"
            review = call("POST", review_path, "postmanA", {
                "state": "COMMENTED", "body": "A review note.", "comments": [{
                    "path": "src/Reviewable.java", "lineNumber": 2, "side": "NEW",
                    "body": "Please clarify this line."}]})
            review_data = body(review)
            check("Owner submits COMMENTED review", review is not None and review.status_code == 201
                  and review_data.get("state") == "COMMENTED" and review_data.get("reviewedHeadCommitHash") == feature, review)
            check("Review identity comes from JWT", review_data.get("reviewer", {}).get("username") == "postmanA", review)
            check("Inline comment captures reviewed snapshot", len(review_data.get("comments", [])) == 1
                  and review_data["comments"][0].get("reviewedHeadCommitHash") == feature, review)
            anonymous_list = call("GET", review_path)
            check("Public reviews readable anonymously", anonymous_list is not None and anonymous_list.status_code == 200
                  and body(anonymous_list).get("totalElements") == 1, anonymous_list)
            anonymous_post = call("POST", review_path, payload={"state": "COMMENTED", "body": "Anonymous"})
            check("Anonymous review submission rejected", anonymous_post is not None and anonymous_post.status_code == 401, anonymous_post)
            self_approval = call("POST", review_path, "postmanA", {"state": "APPROVED", "body": "Self approval"})
            check("Self approval rejected", self_approval is not None and self_approval.status_code == 403, self_approval)
            empty_change = call("POST", review_path, "postmanA", {"state": "CHANGES_REQUESTED"})
            check("Empty change request rejected", empty_change is not None and empty_change.status_code == 400, empty_change)

            standalone = call("POST", comment_path, "postmanA", {
                "path": "src/Reviewable.java", "lineNumber": 1, "side": "NEW", "body": "Standalone note."})
            standalone_id = body(standalone).get("id")
            check("Standalone comment creates COMMENTED review comment", standalone is not None and standalone.status_code == 201
                  and standalone_id and body(standalone).get("reviewedHeadCommitHash") == feature, standalone)
            outsider_edit = call("PUT", f"/api/pull-requests/comments/{standalone_id}", "postmanB", {"body": "Unauthorized edit"})
            check("Non-author cannot edit review comment", outsider_edit is not None and outsider_edit.status_code == 403, outsider_edit)
            owner_edit = call("PUT", f"/api/pull-requests/comments/{standalone_id}", "postmanA", {"body": "Edited note."})
            check("Comment author can edit comment", owner_edit is not None and owner_edit.status_code == 200
                  and body(owner_edit).get("body") == "Edited note.", owner_edit)

            later = call("POST", f"/api/repositories/{repo}/commits", "postmanA", {
                "branch": "feature/reviews", "expectedHeadHash": feature, "message": "Advance source",
                "changes": [{"path": "later.txt", "content": "later"}],
            })
            current = body(later).get("commitHash")
            status_response = call("GET", f"/api/pull-requests/{pr_id}/review-status")
            history = call("GET", review_path)
            historical_hash = body(history).get("reviews", [{}])[0].get("reviewedHeadCommitHash")
            check("Source HEAD advances after existing review", later is not None and later.status_code == 201 and current != feature, later)
            check("Status exposes current HEAD without approval threshold", status_response is not None and status_response.status_code == 200
                  and body(status_response).get("currentSourceHeadHash") == current
                  and body(status_response).get("approvalThresholdConfigured") is False, status_response)
            check("Review history retains original reviewed HEAD", historical_hash == feature, history)

            invalid_file = call("POST", review_path, "postmanA", {"state": "COMMENTED", "comments": [{
                "path": "outside.txt", "lineNumber": 1, "side": "NEW", "body": "Not in diff"}]})
            check("Comment outside PR diff rejected", invalid_file is not None and invalid_file.status_code == 400, invalid_file)
            closed = call("POST", f"/api/pull-requests/{pr_id}/close", "postmanA")
            check("Owner closes PR", closed is not None and closed.status_code == 200, closed)
            after_close = call("POST", review_path, "postmanA", {"state": "COMMENTED", "body": "Late"})
            check("Closed PR rejects new reviews", after_close is not None and after_close.status_code == 409, after_close)
            old_history = call("GET", review_path)
            check("Closed PR preserves review history", old_history is not None and old_history.status_code == 200
                  and body(old_history).get("totalElements") == 2, old_history)

    private_repo = create_repository("postmanA", "PRIVATE")
    if private_repo:
        private_pr = initialize_and_pr("postmanA", private_repo, "PRIVATE")
        if private_pr:
            private_id = private_pr[0]
            check("Private reviews hidden from anonymous users", (lambda r: r is not None and r.status_code == 404)(
                call("GET", f"/api/pull-requests/{private_id}/reviews")))
            check("Private reviews hidden from non-owner", (lambda r: r is not None and r.status_code == 404)(
                call("GET", f"/api/pull-requests/{private_id}/comments", "postmanB")))
            check("Private reviews visible to owner", (lambda r: r is not None and r.status_code == 200)(
                call("GET", f"/api/pull-requests/{private_id}/reviews", "postmanA")))


def main():
    print("PHASE 8D — PULL REQUEST REVIEW HTTP TEST")
    print("Backend:", BASE_URL)
    if not login("postmanA") or not login("postmanB"):
        return 1
    cleanup_password = getpass.getpass("MySQL cleanup password: ")
    baseline = mysql(cleanup_password, "SELECT COALESCE(MAX(id),0) FROM trees; SELECT COALESCE(MAX(id),0) FROM blobs;")
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
        cleanup(cleanup_password, values[0], values[1])
        print(f"\nResults: {passed} passed, {failed} failed")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
