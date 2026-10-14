"""Live HTTP smoke suite for Phase 11A simulated workflow management.

Requires the backend (with Flyway V13 applied) on localhost:8080, the existing
postmanA/postmanB users, and an initialized private test repository owned by
postmanA. Set PHASE11A_REPOSITORY_ID to select it; otherwise the script finds a
private repository named phase11a-* with a main branch. Repository initialization
creates immutable Git history with no delete API, so this suite reuses the fixture
and deletes only workflow/collaborator resources it creates. No shell commands or
repository-provided workflow steps are executed.
"""
import getpass
import json
import os
import time
import urllib.error
import urllib.request
import uuid

BASE_URL = os.environ.get("PHASE11A_BASE_URL", "http://localhost:8080")
tokens = {}
created_workflows = []
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
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
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
    ok = True
    for workflow_id in reversed(created_workflows):
        response = call("DELETE", f"/api/workflows/{workflow_id}", "postmanA")
        success = response is not None and response.status_code in (204, 404)
        check(f"Cleanup workflow {workflow_id}", success, response)
        if not success:
            print(f"[ACTION] Workflow {workflow_id} remains; inspect the API response and retry as postmanA.")
        ok = ok and success
    for repository_id, user_id in reversed(created_collaborators):
        response = call("DELETE", f"/api/repositories/{repository_id}/collaborators/{user_id}", "postmanA")
        success = response is not None and response.status_code in (204, 404)
        check(f"Cleanup collaborator {user_id} from repository {repository_id}", success, response)
        ok = ok and success
    return ok


def select_repository(owner_id, collaborator_id):
    configured_id = os.environ.get("PHASE11A_REPOSITORY_ID")
    candidates = []
    if configured_id:
        try:
            candidates = [int(configured_id)]
        except ValueError:
            check("PHASE11A_REPOSITORY_ID is a numeric repository ID")
            return None
    else:
        response = call("GET", "/api/repositories/mine", "postmanA")
        values = body(response)
        if response is None or response.status_code != 200 or not isinstance(values, list):
            check("List repositories to locate the Phase 11A fixture", False, response)
            return None
        candidates = [item.get("id") for item in values
                      if item.get("visibility") == "PRIVATE"
                      and item.get("owner", {}).get("id") == owner_id
                      and item.get("name", "").startswith("phase11a-")]

    for repository_id in candidates:
        repository = call("GET", f"/api/repositories/{repository_id}", "postmanA")
        details = body(repository)
        if (repository is None or repository.status_code != 200
                or details.get("visibility") != "PRIVATE"
                or details.get("owner", {}).get("id") != owner_id):
            continue
        branches = call("GET", f"/api/repositories/{repository_id}/branches", "postmanA")
        if branches is None or branches.status_code != 200 or not any(
                branch.get("name") == "main" for branch in body(branches) if isinstance(branch, dict)):
            continue
        collaborators = call("GET", f"/api/repositories/{repository_id}/collaborators", "postmanA")
        if collaborators is None or collaborators.status_code != 200:
            continue
        if any(item.get("userId") == collaborator_id for item in body(collaborators) if isinstance(item, dict)):
            continue
        check(f"Use initialized private Phase 11A repository fixture {repository_id}", True)
        return repository_id

    check("Find an initialized private Phase 11A repository fixture", False)
    print("[ACTION] Set PHASE11A_REPOSITORY_ID to a private repository owned by postmanA "
          "with a main branch and no postmanB collaborator. Repository deletion is intentionally "
          "restricted after Git initialization because branch/commit history is immutable.")
    return None


def main():
    print("Phase 11A simulated workflow HTTP tests")
    if not login("postmanA") or not login("postmanB"):
        print("Login failed; no test resources were created.")
        raise SystemExit(1)

    suffix = uuid.uuid4().hex[:10]
    try:
        owner_response = call("GET", "/api/auth/me", "postmanA")
        owner_id = body(owner_response).get("id") or body(owner_response).get("user", {}).get("id")
        collaborator_response = call("GET", "/api/auth/me", "postmanB")
        user_id = body(collaborator_response).get("id") or body(collaborator_response).get("user", {}).get("id")
        check("Read Phase 11A test account IDs", bool(owner_id and user_id), owner_response)
        if not owner_id or not user_id:
            raise RuntimeError("Could not identify the authenticated fixture owners")
        repository_id = select_repository(owner_id, user_id)
        if not repository_id:
            raise RuntimeError("No suitable initialized repository fixture is available")

        config = {"version": 1, "jobs": [
            {"name": "Build", "steps": ["CHECKOUT", "BUILD", "TEST"]},
            {"name": "Package", "steps": ["PACKAGE"]},
        ]}
        invalid = call("POST", f"/api/repositories/{repository_id}/workflows", "postmanA", {
            "name": "Unsafe config", "triggerType": "MANUAL",
            "configuration": {"version": 1, "jobs": [{"name": "bad", "steps": ["SHELL"]}]},
        })
        check("Reject unsupported workflow steps", invalid is not None and invalid.status_code == 400, invalid)

        created = call("POST", f"/api/repositories/{repository_id}/workflows", "postmanA", {
            "name": "Build " + suffix, "description": "Synthetic workflow API check",
            "triggerType": "MANUAL", "configuration": config,
        })
        workflow_id = body(created).get("id")
        check("Owner creates manual workflow", created is not None and created.status_code == 201 and workflow_id, created)
        if not workflow_id:
            raise RuntimeError("Workflow setup failed; no workflow ID was returned")
        created_workflows.append(workflow_id)

        hidden = call("GET", f"/api/workflows/{workflow_id}", "postmanB")
        check("Private workflow is hidden from non-collaborator", hidden is not None and hidden.status_code == 404, hidden)
        invitation = call("POST", f"/api/repositories/{repository_id}/collaborators/invitations", "postmanA",
                          {"username": "postmanB", "role": "READ"})
        invitation_id = body(invitation).get("invitationId")
        check("Invite test reader", invitation is not None and invitation.status_code == 201 and invitation_id, invitation)
        if invitation_id:
            accepted = call("POST", f"/api/collaboration-invitations/{invitation_id}/accept", "postmanB")
            check("Accept reader invitation", accepted is not None and accepted.status_code == 200, accepted)
            if user_id:
                created_collaborators.append((repository_id, user_id))
        visible = call("GET", f"/api/workflows/{workflow_id}", "postmanB")
        check("Accepted READ collaborator can view workflow", visible is not None and visible.status_code == 200, visible)
        denied = call("POST", f"/api/workflows/{workflow_id}/dispatch", "postmanB",
                      {"branchName": "main"})
        check("READ collaborator cannot dispatch workflow", denied is not None and denied.status_code == 403, denied)

        patched = call("PATCH", f"/api/workflows/{workflow_id}", "postmanA", {"description": "Updated synthetic workflow"})
        check("Owner can partially update workflow", patched is not None and patched.status_code == 200
              and body(patched).get("description") == "Updated synthetic workflow", patched)
        disabled = call("POST", f"/api/workflows/{workflow_id}/disable", "postmanA")
        check("Owner disables workflow", disabled is not None and disabled.status_code == 200 and body(disabled).get("enabled") is False, disabled)
        blocked = call("POST", f"/api/workflows/{workflow_id}/dispatch", "postmanA", {"branchName": "main"})
        check("Disabled workflow rejects dispatch", blocked is not None and blocked.status_code == 409, blocked)
        enabled = call("POST", f"/api/workflows/{workflow_id}/enable", "postmanA")
        check("Owner enables workflow", enabled is not None and enabled.status_code == 200 and body(enabled).get("enabled") is True, enabled)

        queued = call("POST", f"/api/workflows/{workflow_id}/dispatch", "postmanA", {"branchName": "main"})
        run_id = body(queued).get("id")
        check("Owner dispatches simulated run", queued is not None and queued.status_code == 202 and run_id
              and body(queued).get("status") == "QUEUED" and body(queued).get("simulated") is True, queued)
        if run_id:
            deadline = time.monotonic() + 25
            latest = queued
            while time.monotonic() < deadline:
                latest = call("GET", f"/api/workflow-runs/{run_id}", "postmanA")
                if body(latest).get("status") in ("SUCCESS", "FAILURE", "CANCELLED"):
                    break
                time.sleep(0.25)
            check("Simulated run reaches a terminal state", latest is not None and body(latest).get("status") == "SUCCESS", latest)
            jobs = call("GET", f"/api/workflow-runs/{run_id}/jobs", "postmanA")
            job_items = body(jobs) if isinstance(body(jobs), list) else []
            check("Run exposes completed synthetic jobs", jobs is not None and jobs.status_code == 200
                  and len(job_items) == 2 and all(job.get("status") == "SUCCESS" for job in job_items), jobs)
            if job_items:
                logs = call("GET", f"/api/workflow-jobs/{job_items[0]['id']}/logs?page=0&size=2", "postmanA")
                check("Job logs are paginated and clearly simulated", logs is not None and logs.status_code == 200
                      and body(logs).get("size") == 2 and body(logs).get("totalElements", 0) >= 2
                      and any("Simulation only" in item.get("message", "") for item in body(logs).get("items", [])), logs)

        failure = call("POST", f"/api/workflows/{workflow_id}/dispatch", "postmanA",
                       {"branchName": "main", "simulatedOutcome": "FAILURE"})
        failure_id = body(failure).get("id")
        check("Dispatch a configured synthetic failure", failure is not None and failure.status_code == 202 and failure_id, failure)
        if failure_id:
            deadline = time.monotonic() + 25
            final = failure
            while time.monotonic() < deadline:
                final = call("GET", f"/api/workflow-runs/{failure_id}", "postmanA")
                if body(final).get("status") in ("SUCCESS", "FAILURE", "CANCELLED"):
                    break
                time.sleep(0.25)
            check("Configured synthetic failure reaches FAILURE", final is not None and body(final).get("status") == "FAILURE", final)

        cancel = call("POST", f"/api/workflows/{workflow_id}/dispatch", "postmanA", {"branchName": "main"})
        cancel_id = body(cancel).get("id")
        check("Queue run for cancellation", cancel is not None and cancel.status_code == 202 and cancel_id, cancel)
        if cancel_id:
            cancelled = call("POST", f"/api/workflow-runs/{cancel_id}/cancel", "postmanA")
            check("Owner cancels queued simulated run", cancelled is not None and cancelled.status_code == 200
                  and body(cancelled).get("status") == "CANCELLED", cancelled)
            after_cancel = call("GET", f"/api/workflow-runs/{cancel_id}", "postmanA")
            check("Cancelled run remains terminal", after_cancel is not None and after_cancel.status_code == 200
                  and body(after_cancel).get("status") == "CANCELLED", after_cancel)
        runs = call("GET", f"/api/repositories/{repository_id}/workflow-runs?page=0&size=10", "postmanA")
        check("Repository run history is available", runs is not None and runs.status_code == 200
              and body(runs).get("totalElements", 0) >= 3, runs)
    except Exception as error:
        check("HTTP workflow completed without an unexpected error", False)
        print(f"[ERROR] {error}")
    finally:
        cleanup()

    print(f"\nRESULT: {passed} passed, {failed} failed")
    raise SystemExit(1 if failed else 0)


if __name__ == "__main__":
    main()
