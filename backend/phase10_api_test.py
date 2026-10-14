"""Phase 10 project and Kanban HTTP checks.

Requires the backend on localhost:8080 and existing postmanA/postmanB accounts.
The script tracks every created resource and only cleans up those identifiers via
the API. Project deletion owns cleanup of its cards and columns.
"""
import getpass
import json
import os
import urllib.error
import urllib.request
import uuid

BASE_URL = os.environ.get("PHASE10_BASE_URL", "http://localhost:8080")
tokens = {}
created_repositories = []
created_repository_projects = []
created_personal_projects = []
created_collaborators = []
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


def cleanup():
    """Best-effort cleanup of this run's tracked resources only; 404 means already absent."""
    ok = True
    for project_id in reversed(created_repository_projects):
        response = call("DELETE", f"/api/projects/{project_id}", "postmanA")
        success = response is not None and response.status_code in (204, 404)
        check(f"Cleanup repository project {project_id}", success, response)
        if response is not None and response.status_code == 404:
            print(f"[INFO] Repository project {project_id} was already absent.")
        if not success:
            print(f"[ACTION] Project {project_id} remains tracked; inspect its API error and retry cleanup as postmanA.")
        ok = ok and success
    for repository_id, user_id in reversed(created_collaborators):
        response = call("DELETE", f"/api/repositories/{repository_id}/collaborators/{user_id}", "postmanA")
        success = response is not None and response.status_code in (204, 404)
        check(f"Cleanup collaborator {user_id} from test repository {repository_id}", success, response)
        ok = ok and success
    for project_id in reversed(created_personal_projects):
        response = call("DELETE", f"/api/projects/{project_id}", "postmanA")
        success = response is not None and response.status_code in (204, 404)
        check(f"Cleanup personal project {project_id}", success, response)
        if not success:
            print(f"[ACTION] Personal project {project_id} remains tracked; inspect its API error and retry cleanup as postmanA.")
        ok = ok and success
    for repository_id in reversed(created_repositories):
        response = call("DELETE", f"/api/repositories/{repository_id}", "postmanA")
        success = response is not None and response.status_code in (204, 404)
        check(f"Cleanup test repository {repository_id}", success, response)
        if response is not None and response.status_code == 409:
            print(f"[ACTION] Repository {repository_id} remains tracked; remove only this run's known dependents before retrying.")
        ok = ok and success
    return ok


def main():
    print("Phase 10 projects and Kanban API tests")
    if not login("postmanA") or not login("postmanB"):
        print("Login failed; no test resources were created.")
        raise SystemExit(1)

    suffix = uuid.uuid4().hex[:10]
    unauthenticated = call("POST", "/api/projects", payload={"scope": "PERSONAL", "name": "Unauthenticated"})
    check("Project writes require authentication", unauthenticated is not None and unauthenticated.status_code == 401, unauthenticated)

    repository = call("POST", "/api/repositories", "postmanA", {"name": "phase10-" + suffix, "visibility": "PRIVATE"})
    repository_id = body(repository).get("id")
    check("Create isolated private repository", repository is not None and repository.status_code == 201 and repository_id, repository)
    if not repository_id:
        print(f"\nRESULT: {passed} passed, {failed} failed")
        raise SystemExit(1)
    created_repositories.append(repository_id)
    try:
        project = call("POST", "/api/projects", "postmanA", {
            "scope": "REPOSITORY", "repositoryId": repository_id, "name": "Board " + suffix,
            "description": "Phase 10 isolated test board"})
        project_id = body(project).get("id")
        check("Owner creates repository project", project is not None and project.status_code == 201 and project_id, project)
        if not project_id:
            raise RuntimeError("Repository project setup failed")
        created_repository_projects.append(project_id)

        anonymous = call("GET", f"/api/projects/{project_id}")
        check("Anonymous user cannot read private repository project", anonymous is not None and anonymous.status_code == 404, anonymous)
        denied = call("GET", f"/api/projects/{project_id}", "postmanB")
        check("Non-collaborator cannot read private project", denied is not None and denied.status_code == 404, denied)

        columns_response = call("GET", f"/api/projects/{project_id}/columns", "postmanA")
        columns = body(columns_response)
        check("Project creates four default columns", columns_response is not None and columns_response.status_code == 200
              and [c.get("name") for c in columns] == ["Todo", "In Progress", "In Review", "Done"], columns_response)
        if not columns:
            raise RuntimeError("Default column setup failed")
        todo = columns[0]["id"]
        invitation = call("POST", f"/api/repositories/{repository_id}/collaborators/invitations", "postmanA",
                          {"username": "postmanB", "role": "WRITE"})
        invitation_id = body(invitation).get("invitationId")
        check("Owner invites write collaborator", invitation is not None and invitation.status_code == 201 and invitation_id, invitation)
        if invitation_id:
            profile = call("GET", "/api/auth/me", "postmanB")
            collaborator_id = body(profile).get("id")
            if collaborator_id:
                created_collaborators.append((repository_id, collaborator_id))
        accepted = call("POST", f"/api/collaboration-invitations/{invitation_id}/accept", "postmanB") if invitation_id else None
        check("Write collaborator accepts invitation", accepted is not None and accepted.status_code == 200, accepted)
        check("Accepted collaborator can read private project",
              (read := call("GET", f"/api/projects/{project_id}", "postmanB")) is not None and read.status_code == 200, read)

        column_denied = call("POST", f"/api/projects/{project_id}/columns", "postmanB", {"name": "Blocked"})
        check("WRITE cannot manage columns", column_denied is not None and column_denied.status_code == 403, column_denied)
        note = call("POST", f"/api/projects/{project_id}/cards", "postmanB",
                    {"columnId": todo, "cardType": "NOTE", "title": "HTTP test task", "description": "Created by phase10_api_test"})
        card_id = body(note).get("id")
        check("WRITE can create a note card", note is not None and note.status_code == 201 and card_id, note)
        edited = call("PATCH", f"/api/project-cards/{card_id}", "postmanB", {"title": "Edited HTTP task"}) if card_id else None
        check("WRITE can edit note card", edited is not None and edited.status_code == 200 and body(edited).get("title") == "Edited HTTP task", edited)

        extra_column = call("POST", f"/api/projects/{project_id}/columns", "postmanA", {"name": "Phase 10"})
        extra_id = body(extra_column).get("id")
        check("Owner adds a column", extra_column is not None and extra_column.status_code == 201 and extra_id, extra_column)
        moved = call("POST", f"/api/project-cards/{card_id}/move", "postmanB", {"columnId": extra_id, "position": 0}) if card_id and extra_id else None
        check("WRITE moves a card", moved is not None and moved.status_code == 200 and body(moved).get("columnId") == extra_id, moved)
        board = call("GET", f"/api/projects/{project_id}/board", "postmanB")
        check("Board returns ordered columns and cards", board is not None and board.status_code == 200
              and len(body(board).get("columns", [])) == 5 and body(board)["columns"][-1]["cards"][0]["id"] == card_id, board)
        cards = call("GET", f"/api/projects/{project_id}/cards?page=0&size=1", "postmanB")
        check("Card list is paginated", cards is not None and cards.status_code == 200
              and body(cards).get("size") == 1 and body(cards).get("totalElements") == 1, cards)
        unsafe_delete = call("DELETE", f"/api/project-columns/{extra_id}", "postmanA")
        check("Column with cards cannot be deleted", unsafe_delete is not None and unsafe_delete.status_code == 409, unsafe_delete)
        after_rejected_delete = call("GET", f"/api/projects/{project_id}/cards?page=0&size=10", "postmanA")
        check("Rejected column delete preserves its card", after_rejected_delete is not None
              and after_rejected_delete.status_code == 200 and any(card.get("id") == card_id for card in body(after_rejected_delete).get("items", [])),
              after_rejected_delete)
        empty_delete = call("DELETE", f"/api/project-columns/{todo}", "postmanA")
        check("Empty column can be deleted", empty_delete is not None and empty_delete.status_code == 204, empty_delete)

        personal = call("POST", "/api/projects", "postmanA", {"scope": "PERSONAL", "name": "Personal " + suffix})
        personal_id = body(personal).get("id")
        check("Owner creates a private personal project", personal is not None and personal.status_code == 201
              and body(personal).get("visibility") == "PRIVATE", personal)
        if personal_id:
            created_personal_projects.append(personal_id)
            private_read = call("GET", f"/api/projects/{personal_id}", "postmanB")
            check("Another user cannot read personal project", private_read is not None and private_read.status_code == 404, private_read)

        archived = call("POST", f"/api/projects/{project_id}/archive", "postmanA")
        check("Owner archives project", archived is not None and archived.status_code == 200 and body(archived).get("status") == "ARCHIVED", archived)
        blocked = call("POST", f"/api/projects/{project_id}/cards", "postmanB",
                       {"columnId": extra_id, "cardType": "NOTE", "title": "Archived write"})
        check("Archived project rejects card writes", blocked is not None and blocked.status_code == 409, blocked)
        restored = call("POST", f"/api/projects/{project_id}/restore", "postmanA")
        check("Owner restores project", restored is not None and restored.status_code == 200 and body(restored).get("status") == "ACTIVE", restored)
    except Exception as error:
        check("HTTP workflow completed without an unexpected error", False)
        print(f"[ERROR] {error}")
    finally:
        cleanup()

    print(f"\nRESULT: {passed} passed, {failed} failed")
    raise SystemExit(1 if failed else 0)


if __name__ == "__main__":
    main()
