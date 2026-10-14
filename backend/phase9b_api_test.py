"""HTTP regression flow for Phase 9B repository discussions.

Requires a running local backend, existing postmanA/postmanB users, and MySQL access for scoped cleanup.
The cleanup targets only repository IDs created by this script.
"""
import getpass
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid

BASE_URL = os.environ.get("PHASE9B_BASE_URL", "http://localhost:8080")
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
    for repository_id in reversed(repositories):
        sql = f"""START TRANSACTION;
SET @phase9b_repo = {int(repository_id)};
DELETE rr FROM discussion_reply_reactions rr JOIN discussion_replies r ON r.id = rr.reply_id
  JOIN repository_discussions d ON d.id = r.discussion_id WHERE d.repository_id = @phase9b_repo;
DELETE FROM discussion_reactions WHERE discussion_id IN
  (SELECT id FROM repository_discussions WHERE repository_id = @phase9b_repo);
UPDATE repository_discussions SET accepted_reply_id = NULL WHERE repository_id = @phase9b_repo;
DELETE FROM discussion_replies WHERE parent_reply_id IS NOT NULL AND discussion_id IN
  (SELECT id FROM repository_discussions WHERE repository_id = @phase9b_repo);
DELETE FROM discussion_replies WHERE discussion_id IN
  (SELECT id FROM repository_discussions WHERE repository_id = @phase9b_repo);
DELETE FROM repository_discussions WHERE repository_id = @phase9b_repo;
DELETE FROM repository_collaborators WHERE repository_id = @phase9b_repo;
DELETE FROM repositories WHERE id = @phase9b_repo;
COMMIT;"""
        result = mysql(password, sql)
        success = result.returncode == 0
        check(f"Cleanup repository {repository_id}", success)
        if not success:
            print(result.stderr[:500])
        ok = success and ok
    return ok


def create_repository(user, name, visibility):
    response = call("POST", "/api/repositories", user, {"name": name, "visibility": visibility})
    check(f"Create {visibility.lower()} test repository", response is not None and response.status_code == 201, response)
    repository_id = body(response).get("id")
    if repository_id:
        repositories.append(repository_id)
    return repository_id


def invite_write(owner, username, repository_id):
    response = call("POST", f"/api/repositories/{repository_id}/collaborators/invitations", owner,
                    {"username": username, "role": "WRITE"})
    check("Invite WRITE collaborator", response is not None and response.status_code == 201, response)
    invitation_id = body(response).get("invitationId")
    accepted = call("POST", f"/api/collaboration-invitations/{invitation_id}/accept", username) if invitation_id else None
    check("WRITE collaborator accepts invitation", accepted is not None and accepted.status_code == 200, accepted)


def create_discussion(user, repository_id, category, title, text):
    return call("POST", f"/api/repositories/{repository_id}/discussions", user,
                {"category": category, "title": title, "body": text})


def main():
    print("Phase 9B repository discussion API tests")
    if not login("postmanA") or not login("postmanB"):
        print("Login failed; tests cannot continue.")
        raise SystemExit(1)
    suffix = uuid.uuid4().hex[:10]
    public_repo = create_repository("postmanA", "phase9b-" + suffix, "PUBLIC")
    private_repo = create_repository("postmanA", "phase9b-private-" + suffix, "PRIVATE")
    if not (public_repo and private_repo):
        cleanup(getpass.getpass("MySQL cleanup password (leave blank if none): "))
        raise SystemExit(1)
    discussion_id = reply_id = None
    try:
        invite_write("postmanA", "postmanB", public_repo)
        created = create_discussion("postmanB", public_repo, "Q_AND_A", "Question " + suffix, "Question body")
        check("WRITE collaborator creates Q&A", created is not None and created.status_code == 201
              and body(created).get("number") == 1, created)
        discussion_id = body(created).get("id")
        public_list = call("GET", f"/api/repositories/{public_repo}/discussions?page=0&size=1")
        check("Anonymous lists public discussions", public_list is not None and public_list.status_code == 200
              and body(public_list).get("totalElements") == 1, public_list)
        detail = call("GET", f"/api/discussions/{discussion_id}")
        check("Anonymous reads public discussion", detail is not None and detail.status_code == 200, detail)
        announcement = create_discussion("postmanB", public_repo, "ANNOUNCEMENTS", "Denied", "Body")
        check("WRITE cannot create announcement", announcement is not None and announcement.status_code == 403, announcement)
        invalid_category = create_discussion("postmanB", public_repo, "INVALID", "Invalid", "Body")
        check("Invalid category is rejected", invalid_category is not None and invalid_category.status_code == 400, invalid_category)

        reply = call("POST", f"/api/discussions/{discussion_id}/replies", "postmanB", {"body": "First answer"})
        check("WRITE collaborator replies", reply is not None and reply.status_code == 201, reply)
        reply_id = body(reply).get("id")
        parent_id = reply_id
        child = call("POST", f"/api/discussions/{discussion_id}/replies", "postmanB",
                     {"body": "One-level follow up", "parentReplyId": parent_id})
        check("One-level nested reply is allowed", child is not None and child.status_code == 201, child)
        deep = call("POST", f"/api/discussions/{discussion_id}/replies", "postmanB",
                    {"body": "Too deep", "parentReplyId": body(child).get("id")})
        check("Deeper nesting is rejected", deep is not None and deep.status_code == 400, deep)
        accepted = call("PUT", f"/api/discussions/{discussion_id}/accepted-answer", "postmanB", {"replyId": reply_id})
        check("Discussion author sets accepted answer", accepted is not None and accepted.status_code == 204, accepted)
        reaction = call("POST", f"/api/discussion-replies/{reply_id}/reactions", "postmanB", {"type": "HEART"})
        repeated = call("POST", f"/api/discussion-replies/{reply_id}/reactions", "postmanB", {"type": "HEART"})
        check("Reaction addition is idempotent", reaction is not None and reaction.status_code == 204
              and repeated is not None and repeated.status_code == 204, repeated)
        replies = call("GET", f"/api/discussions/{discussion_id}/replies?page=0&size=10")
        counts = body(replies).get("items", [{}])[0].get("reactions", {}) if body(replies).get("items") else {}
        check("Reply reaction count is returned", replies is not None and replies.status_code == 200
              and counts.get("HEART") == 1, replies)
        close = call("POST", f"/api/discussions/{discussion_id}/close", "postmanB")
        blocked_reply = call("POST", f"/api/discussions/{discussion_id}/replies", "postmanB", {"body": "Closed"})
        reopen = call("POST", f"/api/discussions/{discussion_id}/reopen", "postmanB")
        check("Close blocks replies and reopen preserves discussion", close is not None and close.status_code == 200
              and blocked_reply is not None and blocked_reply.status_code == 409
              and reopen is not None and reopen.status_code == 200, reopen)
        edited = call("PATCH", f"/api/discussions/{discussion_id}", "postmanB", {"title": "Edited " + suffix})
        check("Author can partially edit discussion", edited is not None and edited.status_code == 200
              and body(edited).get("title") == "Edited " + suffix, edited)
        private_list = call("GET", f"/api/repositories/{private_repo}/discussions")
        check("Anonymous cannot read private discussions", private_list is not None and private_list.status_code == 404, private_list)
        private_attempt = create_discussion("postmanB", private_repo, "GENERAL", "Private", "Body")
        check("Private repository remains isolated", private_attempt is not None and private_attempt.status_code == 404, private_attempt)
        no_delete = call("DELETE", f"/api/discussions/{discussion_id}", "postmanB")
        check("Discussion author cannot delete", no_delete is not None and no_delete.status_code == 403, no_delete)
    finally:
        cleanup_password = getpass.getpass("MySQL cleanup password (leave blank if none): ")
        cleanup(cleanup_password)

    print("\n" + "=" * 56)
    print(f"PASSED: {passed}\nFAILED: {failed}\nTOTAL : {passed + failed}")
    print("ALL PHASE 9B API TESTS PASSED" if failed == 0 else "SOME PHASE 9B API TESTS FAILED")
    print("=" * 56)
    raise SystemExit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
