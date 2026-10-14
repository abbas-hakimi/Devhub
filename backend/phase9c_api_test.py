"""Phase 9C notifications and repository activity HTTP checks.

Requires a running backend and existing postmanA/postmanB accounts. The optional
MySQL cleanup deletes only rows connected to repositories created in this run.
"""
import getpass
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid

BASE_URL = os.environ.get("PHASE9C_BASE_URL", "http://localhost:8080")
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
    success_all = True
    for repository_id in reversed(repositories):
        repo_id = int(repository_id)
        sql = f"""START TRANSACTION;
SET @phase9c_repo = {repo_id};
DELETE FROM user_notifications WHERE repository_id = @phase9c_repo
 OR (target_type = 'REPOSITORY' AND target_id = @phase9c_repo AND event_type = 'COLLABORATOR_INVITED');
DELETE FROM discussion_reactions WHERE discussion_id IN
 (SELECT id FROM repository_discussions WHERE repository_id = @phase9c_repo);
DELETE rr FROM discussion_reply_reactions rr JOIN discussion_replies r ON r.id = rr.reply_id
 JOIN repository_discussions d ON d.id = r.discussion_id WHERE d.repository_id = @phase9c_repo;
UPDATE repository_discussions SET accepted_reply_id = NULL WHERE repository_id = @phase9c_repo;
DELETE FROM discussion_replies WHERE parent_reply_id IS NOT NULL AND discussion_id IN
 (SELECT id FROM repository_discussions WHERE repository_id = @phase9c_repo);
DELETE FROM discussion_replies WHERE discussion_id IN
 (SELECT id FROM repository_discussions WHERE repository_id = @phase9c_repo);
DELETE FROM repository_discussions WHERE repository_id = @phase9c_repo;
DELETE FROM repository_collaborators WHERE repository_id = @phase9c_repo;
DELETE FROM repositories WHERE id = @phase9c_repo;
COMMIT;"""
        result = mysql(password, sql)
        success = result.returncode == 0
        check(f"Cleanup test repository {repo_id}", success)
        if not success:
            print(result.stderr[:500])
        success_all = success and success_all
    return success_all


def create_repository(name):
    response = call("POST", "/api/repositories", "postmanA", {"name": name, "visibility": "PUBLIC"})
    repository_id = body(response).get("id")
    check("Create isolated public repository", response is not None and response.status_code == 201 and repository_id, response)
    if repository_id:
        repositories.append(repository_id)
    return repository_id


def main():
    print("Phase 9C notifications and repository activity API tests")
    if not login("postmanA") or not login("postmanB"):
        print("Login failed; tests cannot continue.")
        raise SystemExit(1)
    suffix = uuid.uuid4().hex[:10]
    repository_id = create_repository("phase9c-" + suffix)
    if not repository_id:
        raise SystemExit(1)
    try:
        invite = call("POST", f"/api/repositories/{repository_id}/collaborators/invitations", "postmanA",
                      {"username": "postmanB", "role": "WRITE"})
        invitation_id = body(invite).get("invitationId")
        check("Invitation creates recipient notification", invite is not None and invite.status_code == 201, invite)
        b_notifications = call("GET", "/api/notifications?unread=true", "postmanB")
        b_items = body(b_notifications).get("items", [])
        invitation_notification = next((n for n in b_items if n.get("eventType") == "COLLABORATOR_INVITED"
                                         and n.get("targetId") == repository_id), None)
        check("Invitee can read invitation notification", invitation_notification is not None, b_notifications)
        accepted = call("POST", f"/api/collaboration-invitations/{invitation_id}/accept", "postmanB") if invitation_id else None
        check("Invitee accepts collaboration", accepted is not None and accepted.status_code == 200, accepted)
        invitation_owner_notifs = call("GET", "/api/notifications?unread=true", "postmanA")
        check("Owner is notified when invitation is accepted",
              any(n.get("eventType") == "COLLABORATOR_ACCEPTED" for n in body(invitation_owner_notifs).get("items", [])),
              invitation_owner_notifs)

        discussion = call("POST", f"/api/repositories/{repository_id}/discussions", "postmanB",
                          {"category": "Q_AND_A", "title": "Question " + suffix, "body": "Question details"})
        discussion_id = body(discussion).get("id")
        check("Discussion creation succeeds", discussion is not None and discussion.status_code == 201, discussion)
        reply = call("POST", f"/api/discussions/{discussion_id}/replies", "postmanA", {"body": "Answer"}) if discussion_id else None
        reply_id = body(reply).get("id")
        check("Reply creation succeeds", reply is not None and reply.status_code == 201, reply)
        owner_notifs = call("GET", "/api/notifications?unread=true", "postmanB")
        check("Discussion author receives reply notification",
              any(n.get("eventType") == "DISCUSSION_REPLIED" and n.get("targetId") == discussion_id
                  for n in body(owner_notifs).get("items", [])), owner_notifs)
        accepted_answer = call("PUT", f"/api/discussions/{discussion_id}/accepted-answer", "postmanB",
                               {"replyId": reply_id}) if reply_id else None
        check("Author accepts answer", accepted_answer is not None and accepted_answer.status_code == 204, accepted_answer)
        a_notifications = call("GET", "/api/notifications", "postmanA")
        check("Answer author receives accepted-answer notification",
              any(n.get("eventType") == "DISCUSSION_ANSWER_ACCEPTED" and n.get("targetId") == discussion_id
                  for n in body(a_notifications).get("items", [])), a_notifications)
        activity = call("GET", f"/api/repositories/{repository_id}/activity?page=0&size=100")
        event_types = [item.get("eventType") for item in body(activity).get("items", [])]
        check("Public activity feed is readable anonymously", activity is not None and activity.status_code == 200, activity)
        check("Activity includes repository, discussion, and accepted-answer events",
              all(event in event_types for event in ("REPOSITORY_CREATED", "DISCUSSION_CREATED", "DISCUSSION_ANSWER_ACCEPTED")), activity)
        unread_count = call("GET", "/api/notifications/unread-count", "postmanA")
        check("Unread count endpoint responds", unread_count is not None and unread_count.status_code == 200, unread_count)
        if invitation_notification:
            mark = call("PATCH", f"/api/notifications/{invitation_notification['id']}/read", "postmanB")
            check("Recipient marks notification read", mark is not None and mark.status_code == 200 and body(mark).get("readAt"), mark)
    finally:
        password = getpass.getpass("MySQL cleanup password (leave blank if none): ")
        cleanup(password)
    print(f"\nRESULT: {passed} passed, {failed} failed")
    raise SystemExit(1 if failed else 0)


if __name__ == "__main__":
    main()
