"""HTTP regression flow for Phase 9A repository tags and releases.

Requires the local backend, existing postmanA/postmanB users, and MySQL access for cleanup.
The script creates isolated repositories and only deletes records belonging to those IDs.
"""
import getpass
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid

BASE_URL = os.environ.get("PHASE9A_BASE_URL", "http://localhost:8080")
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
SET @phase9a_repo = {int(repository_id)};
DELETE FROM repository_releases WHERE repository_id = @phase9a_repo;
DELETE FROM repository_tags WHERE repository_id = @phase9a_repo;
DELETE FROM repository_collaborators WHERE repository_id = @phase9a_repo;
DELETE FROM branches WHERE repository_id = @phase9a_repo;
UPDATE commits SET parent_commit_id = NULL, second_parent_commit_id = NULL WHERE repository_id = @phase9a_repo;
DELETE FROM commits WHERE repository_id = @phase9a_repo;
DELETE FROM repositories WHERE id = @phase9a_repo;
COMMIT;"""
        result = mysql(password, sql)
        success = result.returncode == 0
        check(f"Cleanup repository {repository_id}", success)
        if not success:
            print(result.stderr[:500])
        ok = success and ok
    return ok


def repository(user, name, visibility):
    response = call("POST", "/api/repositories", user, {"name": name, "visibility": visibility})
    check(f"Create {visibility.lower()} test repository", response is not None and response.status_code == 201, response)
    repository_id = body(response).get("id")
    if repository_id:
        repositories.append(repository_id)
    return repository_id


def initialize(user, repository_id):
    response = call("POST", f"/api/repositories/{repository_id}/git/init", user)
    check("Initialize repository", response is not None and response.status_code == 201, response)
    return body(response).get("commitHash")


def create_tag(user, repository_id, name, commit_hash):
    return call("POST", f"/api/repositories/{repository_id}/tags", user,
                {"name": name, "commitHash": commit_hash})


def invite_write(owner, username, repository_id):
    response = call("POST", f"/api/repositories/{repository_id}/collaborators/invitations", owner,
                    {"username": username, "role": "WRITE"})
    check("Invite WRITE collaborator", response is not None and response.status_code == 201, response)
    invitation_id = body(response).get("invitationId")
    accepted = call("POST", f"/api/collaboration-invitations/{invitation_id}/accept", username) if invitation_id else None
    check("WRITE collaborator accepts invitation", accepted is not None and accepted.status_code == 200, accepted)


def main():
    print("Phase 9A tag and release API tests")
    if not login("postmanA") or not login("postmanB"):
        print("Login failed; tests cannot continue.")
        raise SystemExit(1)

    suffix = uuid.uuid4().hex[:10]
    repository_id = repository("postmanA", "phase9a-" + suffix, "PUBLIC")
    other_repository_id = repository("postmanA", "phase9a-other-" + suffix, "PUBLIC")
    private_repository_id = repository("postmanA", "phase9a-private-" + suffix, "PRIVATE")
    if not (repository_id and other_repository_id and private_repository_id):
        raise SystemExit(1)
    commit_hash = initialize("postmanA", repository_id)
    other_commit_hash = initialize("postmanA", other_repository_id)
    initialize("postmanA", private_repository_id)
    invite_write("postmanA", "postmanB", repository_id)

    tag_name = "v9.0.0-" + suffix
    created = create_tag("postmanB", repository_id, tag_name, commit_hash)
    check("WRITE collaborator creates tag", created is not None and created.status_code == 201
          and body(created).get("commitHash") == commit_hash, created)
    public_tags = call("GET", f"/api/repositories/{repository_id}/tags")
    check("Anonymous lists public tags", public_tags is not None and public_tags.status_code == 200, public_tags)
    detail = call("GET", f"/api/repositories/{repository_id}/tags/{tag_name}")
    check("Anonymous reads public tag", detail is not None and detail.status_code == 200
          and body(detail).get("commitHash") == commit_hash, detail)
    duplicate = create_tag("postmanB", repository_id, tag_name, commit_hash)
    check("Duplicate tag returns conflict", duplicate is not None and duplicate.status_code == 409, duplicate)
    invalid = create_tag("postmanB", repository_id, "../secret", commit_hash)
    check("Path-like tag name is rejected", invalid is not None and invalid.status_code == 400, invalid)
    cross_repo = create_tag("postmanA", repository_id, "cross-repository", other_commit_hash)
    check("Cross-repository commit tag is rejected", cross_repo is not None and cross_repo.status_code == 404, cross_repo)
    page = call("GET", f"/api/repositories/{repository_id}/tags?page=0&size=100")
    check("Tag list returns page metadata", page is not None and page.status_code == 200
          and body(page).get("items") is not None and body(page).get("totalElements") == 1, page)

    draft = call("POST", f"/api/repositories/{repository_id}/releases", "postmanA", {
        "tagName": tag_name, "title": "Version 9", "description": "Draft notes", "draft": True,
    })
    check("Owner creates draft release", draft is not None and draft.status_code == 201
          and body(draft).get("draft") is True and body(draft).get("publishedAt") is None, draft)
    release_id = body(draft).get("id")
    drafts_public = call("GET", f"/api/repositories/{repository_id}/releases")
    check("Anonymous cannot see draft release", drafts_public is not None and drafts_public.status_code == 200
          and body(drafts_public).get("totalElements") == 0, drafts_public)
    writer_draft = call("GET", f"/api/repositories/{repository_id}/releases/{release_id}", "postmanB") if release_id else None
    check("WRITE collaborator cannot read draft release", writer_draft is not None and writer_draft.status_code == 404, writer_draft)
    denied_create = call("POST", f"/api/repositories/{repository_id}/releases", "postmanB", {
        "tagName": tag_name, "title": "Writer release", "draft": True,
    })
    check("WRITE collaborator cannot create release", denied_create is not None and denied_create.status_code == 403, denied_create)

    if release_id:
        blocked_tag_delete = call("DELETE", f"/api/repositories/{repository_id}/tags/{tag_name}", "postmanA")
        check("Tag referenced by release cannot be deleted", blocked_tag_delete is not None
              and blocked_tag_delete.status_code == 409, blocked_tag_delete)
        published = call("PATCH", f"/api/repositories/{repository_id}/releases/{release_id}", "postmanA", {"draft": False})
        published_at = body(published).get("publishedAt")
        check("Owner publishes draft", published is not None and published.status_code == 200
              and body(published).get("draft") is False and published_at, published)
        anonymous_release = call("GET", f"/api/repositories/{repository_id}/releases/{release_id}")
        check("Anonymous reads published release", anonymous_release is not None and anonymous_release.status_code == 200, anonymous_release)
        edited = call("PATCH", f"/api/repositories/{repository_id}/releases/{release_id}", "postmanA",
                      {"title": "Version 9.0", "prerelease": True})
        check("Release edit preserves publication timestamp", edited is not None and edited.status_code == 200
              and body(edited).get("title") == "Version 9.0" and body(edited).get("publishedAt") == published_at, edited)
        unpublished = call("PATCH", f"/api/repositories/{repository_id}/releases/{release_id}", "postmanA", {"draft": True})
        check("Owner returns release to draft", unpublished is not None and unpublished.status_code == 200
              and body(unpublished).get("draft") is True and body(unpublished).get("publishedAt") is None, unpublished)
        deleted_release = call("DELETE", f"/api/repositories/{repository_id}/releases/{release_id}", "postmanA")
        check("Release deletion leaves tag available", deleted_release is not None and deleted_release.status_code == 204, deleted_release)
        deleted_tag = call("DELETE", f"/api/repositories/{repository_id}/tags/{tag_name}", "postmanA")
        check("Maintainer deletes unreferenced tag", deleted_tag is not None and deleted_tag.status_code == 204, deleted_tag)

    hidden_tags = call("GET", f"/api/repositories/{private_repository_id}/tags")
    hidden_releases = call("GET", f"/api/repositories/{private_repository_id}/releases")
    check("Anonymous private tag list is hidden", hidden_tags is not None and hidden_tags.status_code == 404, hidden_tags)
    check("Anonymous private release list is hidden", hidden_releases is not None and hidden_releases.status_code == 404, hidden_releases)

    cleanup_password = getpass.getpass("MySQL cleanup password (leave blank if none): ")
    cleanup(cleanup_password)
    print("\n" + "=" * 56)
    print(f"PASSED: {passed}\nFAILED: {failed}\nTOTAL : {passed + failed}")
    print("ALL PHASE 9A API TESTS PASSED" if failed == 0 else "SOME PHASE 9A API TESTS FAILED")
    print("=" * 56)
    raise SystemExit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
