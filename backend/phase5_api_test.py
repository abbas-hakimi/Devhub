import getpass
import sys
import time
from datetime import datetime

import requests

BASE_URL = "http://localhost:8080"
TIMEOUT = 10

session = requests.Session()
passed = 0
failed = 0
created_repo_ids = []


def test(name, condition, response=None):
    global passed, failed

    if condition:
        passed += 1
        print(f"[PASS] {name}")
    else:
        failed += 1
        print(f"[FAIL] {name}")
        if response is not None:
            try:
                print(f"       HTTP {response.status_code}: {response.text[:500]}")
            except Exception:
                pass


def auth_headers(token):
    return {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }


def json_or_empty(response):
    try:
        return response.json()
    except ValueError:
        return {}


def login(identifier, password, label):
    try:
        response = session.post(
            f"{BASE_URL}/api/auth/login",
            json={"identifier": identifier, "password": password},
            timeout=TIMEOUT,
        )
    except requests.RequestException as exc:
        test(label, False)
        print(f"       Request error: {exc}")
        return None, None

    data = json_or_empty(response)
    token = data.get("accessToken")

    test(label, response.status_code == 200 and bool(token), response)
    return token, response


def delete_repo(repo_id, token):
    try:
        response = session.delete(
            f"{BASE_URL}/api/repositories/{repo_id}",
            headers=auth_headers(token),
            timeout=TIMEOUT,
        )
        return response
    except requests.RequestException:
        return None


print("=" * 65)
print("PHASE 5 — ISSUE TRACKING API TEST SUITE")
print("=" * 65)
print()
print(f"Backend: {BASE_URL}")
print()

password_a = getpass.getpass("Password for postmanA: ")
password_b = getpass.getpass("Password for postmanB: ")

# -----------------------------------------------------------------
# AUTHENTICATION
# -----------------------------------------------------------------
print()
print("-" * 65)
print("AUTHENTICATION")
print("-" * 65)

token_a, _ = login("postmanA", password_a, "User A login")
token_b, _ = login("postmanB", password_b, "User B login")

if not token_a or not token_b:
    print("\nCannot continue without both JWT tokens.")
    sys.exit(1)

response = session.get(
    f"{BASE_URL}/api/auth/me",
    headers=auth_headers(token_a),
    timeout=TIMEOUT,
)
test("User A /me", response.status_code == 200, response)

response = session.get(
    f"{BASE_URL}/api/auth/me",
    headers=auth_headers(token_b),
    timeout=TIMEOUT,
)
test("User B /me", response.status_code == 200, response)

# -----------------------------------------------------------------
# REPOSITORY SETUP
# -----------------------------------------------------------------
print()
print("-" * 65)
print("REPOSITORY SETUP")
print("-" * 65)

# IMPORTANT:
# Use a unique suffix on every run so old test repositories never
# cause HTTP 409 duplicate-name failures.
run_id = datetime.now().strftime("%Y%m%d%H%M%S")

public_name = f"phase5-auto-public-{run_id}"
private_name = f"phase5-auto-private-{run_id}"

response = session.post(
    f"{BASE_URL}/api/repositories",
    headers=auth_headers(token_a),
    json={
        "name": public_name,
        "description": "Automated Phase 5 public repository",
        "visibility": "PUBLIC",
    },
    timeout=TIMEOUT,
)
public_data = json_or_empty(response)
public_repo_id = public_data.get("id")
test(
    "Create public repository",
    response.status_code == 201 and bool(public_repo_id),
    response,
)

if public_repo_id:
    created_repo_ids.append(public_repo_id)

response = session.post(
    f"{BASE_URL}/api/repositories",
    headers=auth_headers(token_a),
    json={
        "name": private_name,
        "description": "Automated Phase 5 private repository",
        "visibility": "PRIVATE",
    },
    timeout=TIMEOUT,
)
private_data = json_or_empty(response)
private_repo_id = private_data.get("id")
test(
    "Create private repository",
    response.status_code == 201 and bool(private_repo_id),
    response,
)

if private_repo_id:
    created_repo_ids.append(private_repo_id)

if not public_repo_id or not private_repo_id:
    print("\nCould not create test repositories.")
    print("The script uses unique repository names, so this should only happen if the backend is unavailable or repository creation itself fails.")
    sys.exit(1)

print()
print(f"Public repository ID : {public_repo_id}")
print(f"Private repository ID: {private_repo_id}")

# -----------------------------------------------------------------
# ISSUES
# -----------------------------------------------------------------
print()
print("-" * 65)
print("ISSUES")
print("-" * 65)

response = session.post(
    f"{BASE_URL}/api/repositories/{public_repo_id}/issues",
    headers=auth_headers(token_b),
    json={
        "title": "Automated Phase 5 issue",
        "description": "Testing Phase 5 issue creation",
    },
    timeout=TIMEOUT,
)
issue_data = json_or_empty(response)
issue_id = issue_data.get("id")
test(
    "User B creates issue in public repository",
    response.status_code == 201 and bool(issue_id),
    response,
)

if not issue_id:
    print("\nCannot continue without an issue.")
    sys.exit(1)

print(f"Issue ID: {issue_id}")

response = session.get(
    f"{BASE_URL}/api/issues/{issue_id}",
    headers=auth_headers(token_b),
    timeout=TIMEOUT,
)
test("Get issue", response.status_code == 200, response)

response = session.get(
    f"{BASE_URL}/api/repositories/{public_repo_id}/issues",
    headers=auth_headers(token_b),
    timeout=TIMEOUT,
)
test("List repository issues", response.status_code == 200, response)

response = session.get(
    f"{BASE_URL}/api/repositories/{public_repo_id}/issues?status=OPEN",
    headers=auth_headers(token_b),
    timeout=TIMEOUT,
)
test("Filter OPEN issues", response.status_code == 200, response)

response = session.get(
    f"{BASE_URL}/api/repositories/{public_repo_id}/issues?status=CLOSED",
    headers=auth_headers(token_b),
    timeout=TIMEOUT,
)
test("Filter CLOSED issues", response.status_code == 200, response)

response = session.put(
    f"{BASE_URL}/api/issues/{issue_id}",
    headers=auth_headers(token_b),
    json={
        "title": "Updated automated issue",
        "description": "Updated description",
    },
    timeout=TIMEOUT,
)
test(
    "Issue author can update title/description",
    response.status_code == 200,
    response,
)

response = session.put(
    f"{BASE_URL}/api/issues/{issue_id}",
    headers=auth_headers(token_b),
    json={"status": "CLOSED"},
    timeout=TIMEOUT,
)
test(
    "Non-owner cannot change issue status",
    response.status_code == 403,
    response,
)

response = session.put(
    f"{BASE_URL}/api/issues/{issue_id}",
    headers=auth_headers(token_a),
    json={"status": "CLOSED"},
    timeout=TIMEOUT,
)
test(
    "Repository owner can close issue",
    response.status_code == 200,
    response,
)

closed_data = json_or_empty(response)
test(
    "Closing issue sets closedAt",
    bool(closed_data.get("closedAt")),
    response,
)

response = session.put(
    f"{BASE_URL}/api/issues/{issue_id}",
    headers=auth_headers(token_a),
    json={"status": "OPEN"},
    timeout=TIMEOUT,
)
test(
    "Repository owner can reopen issue",
    response.status_code == 200,
    response,
)

reopened_data = json_or_empty(response)
test(
    "Reopening issue clears closedAt",
    reopened_data.get("closedAt") is None,
    response,
)

# -----------------------------------------------------------------
# COMMENTS
# -----------------------------------------------------------------
print()
print("-" * 65)
print("COMMENTS")
print("-" * 65)

response = session.post(
    f"{BASE_URL}/api/issues/{issue_id}/comments",
    headers=auth_headers(token_b),
    json={"body": "Automated Phase 5 comment"},
    timeout=TIMEOUT,
)
comment_data = json_or_empty(response)
comment_id = comment_data.get("id")
test(
    "User B creates comment",
    response.status_code == 201 and bool(comment_id),
    response,
)

response = session.get(
    f"{BASE_URL}/api/issues/{issue_id}/comments",
    headers=auth_headers(token_b),
    timeout=TIMEOUT,
)
test("List issue comments", response.status_code == 200, response)

response = session.put(
    f"{BASE_URL}/api/issues/comments/{comment_id}",
    headers=auth_headers(token_b),
    json={"body": "Updated automated comment"},
    timeout=TIMEOUT,
)
test(
    "Comment author can edit own comment",
    response.status_code == 200,
    response,
)

response = session.put(
    f"{BASE_URL}/api/issues/comments/{comment_id}",
    headers=auth_headers(token_a),
    json={"body": "Moderated automated comment"},
    timeout=TIMEOUT,
)
test(
    "Repository owner can moderate comment",
    response.status_code == 200,
    response,
)

# -----------------------------------------------------------------
# LABELS
# -----------------------------------------------------------------
print()
print("-" * 65)
print("LABELS")
print("-" * 65)

response = session.post(
    f"{BASE_URL}/api/repositories/{public_repo_id}/labels",
    headers=auth_headers(token_a),
    json={
        "name": "automated",
        "color": "#123456",
        "description": "Automated test label",
    },
    timeout=TIMEOUT,
)
label_data = json_or_empty(response)
label_id = label_data.get("id")
test(
    "Owner creates label",
    response.status_code == 201 and bool(label_id),
    response,
)

response = session.get(
    f"{BASE_URL}/api/repositories/{public_repo_id}/labels",
    headers=auth_headers(token_b),
    timeout=TIMEOUT,
)
test("List repository labels", response.status_code == 200, response)

response = session.post(
    f"{BASE_URL}/api/repositories/{public_repo_id}/labels",
    headers=auth_headers(token_a),
    json={
        "name": "automated",
        "color": "#654321",
        "description": "Duplicate label",
    },
    timeout=TIMEOUT,
)
test(
    "Duplicate label rejected",
    response.status_code == 409,
    response,
)

response = session.post(
    f"{BASE_URL}/api/repositories/{public_repo_id}/labels",
    headers=auth_headers(token_a),
    json={
        "name": "bad-color",
        "color": "not-a-color",
        "description": "Invalid color",
    },
    timeout=TIMEOUT,
)
test(
    "Invalid label color rejected",
    response.status_code in (400, 422),
    response,
)

response = session.put(
    f"{BASE_URL}/api/labels/{label_id}",
    headers=auth_headers(token_a),
    json={
        "name": "automated-updated",
        "color": "#abcdef",
        "description": "Updated automated label",
    },
    timeout=TIMEOUT,
)
test(
    "Owner can update label",
    response.status_code == 200,
    response,
)

# -----------------------------------------------------------------
# ISSUE LABELS
# -----------------------------------------------------------------
print()
print("-" * 65)
print("ISSUE LABELS")
print("-" * 65)

response = session.post(
    f"{BASE_URL}/api/issues/{issue_id}/labels/{label_id}",
    headers=auth_headers(token_a),
    timeout=TIMEOUT,
)

# The API may legitimately use 200, 201, or 204 for a successful
# relationship creation. Accept all three.
test(
    "Owner attaches label to issue",
    response.status_code in (200, 201, 204),
    response,
)

response = session.post(
    f"{BASE_URL}/api/issues/{issue_id}/labels/{label_id}",
    headers=auth_headers(token_a),
    timeout=TIMEOUT,
)
test(
    "Duplicate issue-label attachment rejected",
    response.status_code == 409,
    response,
)

response = session.get(
    f"{BASE_URL}/api/issues/{issue_id}",
    headers=auth_headers(token_b),
    timeout=TIMEOUT,
)
issue_after_label = json_or_empty(response)
labels = issue_after_label.get("labels") or []
label_present = any(
    str(label.get("id")) == str(label_id)
    for label in labels
    if isinstance(label, dict)
)
test(
    "Issue contains attached label",
    response.status_code == 200 and label_present,
    response,
)

response = session.delete(
    f"{BASE_URL}/api/issues/{issue_id}/labels/{label_id}",
    headers=auth_headers(token_a),
    timeout=TIMEOUT,
)
test(
    "Owner detaches label",
    response.status_code in (200, 204),
    response,
)

# -----------------------------------------------------------------
# PRIVATE REPOSITORY SECURITY
# -----------------------------------------------------------------
print()
print("-" * 65)
print("PRIVATE REPOSITORY SECURITY")
print("-" * 65)

response = session.post(
    f"{BASE_URL}/api/repositories/{private_repo_id}/issues",
    headers=auth_headers(token_b),
    json={
        "title": "Unauthorized private issue",
        "description": "Should not be allowed",
    },
    timeout=TIMEOUT,
)
test(
    "Non-owner cannot create private issue",
    response.status_code in (403, 404),
    response,
)

response = session.get(
    f"{BASE_URL}/api/repositories/{private_repo_id}/issues",
    headers=auth_headers(token_b),
    timeout=TIMEOUT,
)
test(
    "Non-owner cannot list private issues",
    response.status_code in (403, 404),
    response,
)

# -----------------------------------------------------------------
# AUTHORIZATION / IDOR
# -----------------------------------------------------------------
print()
print("-" * 65)
print("AUTHORIZATION / IDOR")
print("-" * 65)

response = session.delete(
    f"{BASE_URL}/api/issues/{issue_id}",
    headers=auth_headers(token_b),
    timeout=TIMEOUT,
)
test(
    "Non-owner cannot delete issue",
    response.status_code == 403,
    response,
)

response = session.put(
    f"{BASE_URL}/api/labels/{label_id}",
    headers=auth_headers(token_b),
    json={
        "name": "unauthorized-update",
        "color": "#ffffff",
        "description": "Should not be allowed",
    },
    timeout=TIMEOUT,
)
test(
    "Non-owner cannot update label",
    response.status_code == 403,
    response,
)

# -----------------------------------------------------------------
# CLEANUP
# -----------------------------------------------------------------
print()
print("-" * 65)
print("CLEANUP")
print("-" * 65)

if comment_id:
    response = session.delete(
        f"{BASE_URL}/api/issues/comments/{comment_id}",
        headers=auth_headers(token_b),
        timeout=TIMEOUT,
    )
    test("Delete test comment", response.status_code in (200, 204), response)

# Label was already detached above.
response = session.delete(
    f"{BASE_URL}/api/labels/{label_id}",
    headers=auth_headers(token_a),
    timeout=TIMEOUT,
)
test("Delete test label", response.status_code in (200, 204), response)

response = session.delete(
    f"{BASE_URL}/api/issues/{issue_id}",
    headers=auth_headers(token_a),
    timeout=TIMEOUT,
)
test("Delete test issue", response.status_code in (200, 204), response)

# Remove the temporary repositories too, so the database stays clean.
for repo_id, label in (
    (public_repo_id, "Delete test public repository"),
    (private_repo_id, "Delete test private repository"),
):
    response = delete_repo(repo_id, token_a)
    test(
        label,
        response is not None and response.status_code in (200, 204),
        response,
    )

# -----------------------------------------------------------------
# FINAL RESULT
# -----------------------------------------------------------------
print()
print("=" * 65)
print("FINAL RESULT")
print("=" * 65)
print(f"PASSED: {passed}")
print(f"FAILED: {failed}")
print(f"TOTAL : {passed + failed}")
print()

if failed == 0:
    print("ALL PHASE 5 TESTS PASSED")
else:
    print("SOME TESTS FAILED")

print("=" * 65)
