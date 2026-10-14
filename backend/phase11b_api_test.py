"""Cookie-aware Phase 11B live HTTP tests.

Requires a running backend, `requests`, and existing postmanA/postmanB accounts.
The script prompts for both passwords and revokes only sessions whose unique
User-Agent was created by this run. It never creates repositories or users.
"""
import getpass
import os
import sys
import uuid

import requests

BASE_URL = os.environ.get("PHASE11B_BASE_URL", "http://localhost:8080").rstrip("/")
ORIGIN = os.environ.get("PHASE11B_FRONTEND_ORIGIN", "http://localhost:3000")
COOKIE_NAME = os.environ.get("PHASE11B_REFRESH_COOKIE", "DEVHUB_REFRESH")
USERS = ("postmanA", "postmanB")
run_id = uuid.uuid4().hex[:16]
clients = {user: [] for user in USERS}
created_sessions = {user: set() for user in USERS}
passed = failed = 0


def request(client, method, path, *, origin=True, **kwargs):
    headers = dict(kwargs.pop("headers", {}))
    if origin and "Origin" not in headers:
        headers["Origin"] = ORIGIN
    try:
        return client.request(method, BASE_URL + path, headers=headers, timeout=20, **kwargs)
    except requests.RequestException as error:
        print(f"[REQUEST ERROR] {method} {path}: {error}")
        return None


def check(label, condition, response=None):
    global passed, failed
    if condition:
        passed += 1
        print("[PASS] " + label)
    else:
        failed += 1
        detail = f" HTTP {response.status_code}: {response.text[:300]}" if response is not None else ""
        print("[FAIL] " + label + detail)


def json_body(response):
    try:
        return response.json() if response is not None else {}
    except ValueError:
        return {}


def track_sessions(user, client):
    response = request(client, "GET", "/api/auth/sessions")
    records = json_body(response)
    if response is not None and response.status_code == 200 and isinstance(records, list):
        created_sessions[user].update(
            item["sessionId"] for item in records
            if item.get("userAgent") == client.marker and item.get("sessionId") is not None
        )
    return response, records


def login(user, password, label):
    client = requests.Session()
    client.marker = f"phase11b-{run_id}-{label}"
    client.headers["User-Agent"] = client.marker
    response = request(client, "POST", "/api/auth/login", origin=False,
                       json={"identifier": user, "password": password})
    body = json_body(response)
    token = body.get("accessToken")
    cookie_header = response.headers.get("Set-Cookie", "") if response else ""
    valid = (response is not None and response.status_code == 200 and token
             and COOKIE_NAME in client.cookies and "HttpOnly" in cookie_header
             and "Path=/api/auth" in cookie_header and "SameSite=Lax" in cookie_header
             and "Domain=" not in cookie_header and "refreshToken" not in body)
    check(f"Login {user} issues access token and secure-scope refresh cookie", valid, response)
    if not valid:
        return None
    client.access_token = token
    client.headers["Authorization"] = "Bearer " + token
    clients[user].append(client)
    session_response, records = track_sessions(user, client)
    check(f"Session list is available for {user}",
          session_response is not None and session_response.status_code == 200
          and isinstance(records, list), session_response)
    return client


def cleanup():
    for user in USERS:
        # Refresh tokens may have been rotated/revoked; existing access JWTs are
        # still accepted for session administration until their short expiry.
        admin = next((item for item in reversed(clients[user]) if item.access_token), None)
        if admin is None:
            continue
        response, records = track_sessions(user, admin)
        if response is None or response.status_code != 200:
            check(f"Cleanup can list sessions for {user}", False, response)
            continue
        for session_id in sorted(created_sessions[user]):
            result = request(admin, "DELETE", f"/api/auth/sessions/{session_id}")
            check(f"Cleanup session {session_id} for {user}",
                  result is not None and result.status_code in (204, 404), result)


def run_flow():
    passwords = {user: getpass.getpass(f"Password for {user}: ") for user in USERS}
    owner = login("postmanA", passwords["postmanA"], "owner-one")
    other = login("postmanB", passwords["postmanB"], "other-one")
    if owner is None or other is None:
        return

    me = request(owner, "GET", "/api/auth/me", origin=False)
    check("Bearer access JWT works on /api/auth/me", me is not None and me.status_code == 200, me)

    original_refresh = owner.cookies.get(COOKIE_NAME)
    rotated = request(owner, "POST", "/api/auth/refresh")
    rotated_body = json_body(rotated)
    rotated_refresh = owner.cookies.get(COOKIE_NAME)
    check("Refresh rotates cookie and returns a new access JWT",
          rotated is not None and rotated.status_code == 200 and rotated_body.get("accessToken")
          and rotated_refresh != original_refresh and "refreshToken" not in rotated_body, rotated)

    replay_client = requests.Session()
    replay = request(replay_client, "POST", "/api/auth/refresh",
                     cookies={COOKIE_NAME: original_refresh})
    check("Reusing a rotated token is rejected", replay is not None and replay.status_code == 401, replay)
    replay_followup = request(owner, "POST", "/api/auth/refresh", cookies={COOKIE_NAME: rotated_refresh})
    check("Replay revokes the complete token family",
          replay_followup is not None and replay_followup.status_code == 401, replay_followup)

    second_owner = login("postmanA", passwords["postmanA"], "owner-two")
    if second_owner:
        listing, records = track_sessions("postmanA", second_owner)
        check("Multiple independent sessions are listed without token values",
              listing is not None and listing.status_code == 200 and len(records) >= 2
              and all("tokenHash" not in item and "refreshToken" not in item for item in records), listing)
        owned_id = next((item["sessionId"] for item in records
                         if item.get("userAgent") == second_owner.marker), None)
        cross_user = request(other, "DELETE", f"/api/auth/sessions/{owned_id}") if owned_id else None
        check("Cross-user session revocation is hidden with 404",
              cross_user is not None and cross_user.status_code == 404, cross_user)
        if owned_id:
            revoked = request(second_owner, "DELETE", f"/api/auth/sessions/{owned_id}")
            check("Caller can revoke own session", revoked is not None and revoked.status_code == 204, revoked)
            check("Individually revoked session cannot refresh",
                  request(second_owner, "POST", "/api/auth/refresh").status_code == 401)

    missing_origin = request(requests.Session(), "POST", "/api/auth/logout", origin=False,
                             cookies={COOKIE_NAME: other.cookies.get(COOKIE_NAME)})
    check("Cookie logout rejects absent Origin and Referer",
          missing_origin is not None and missing_origin.status_code == 403, missing_origin)
    hostile = request(other, "POST", "/api/auth/refresh", headers={"Origin": "https://evil.invalid"})
    check("Refresh rejects untrusted Origin", hostile is not None and hostile.status_code == 403, hostile)

    preflight = requests.options(BASE_URL + "/api/auth/refresh", headers={
        "Origin": ORIGIN,
        "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "content-type",
    }, timeout=20)
    check("CORS preflight allows configured credentialed origin",
          preflight.status_code == 200
          and preflight.headers.get("Access-Control-Allow-Origin") == ORIGIN
          and preflight.headers.get("Access-Control-Allow-Credentials") == "true", preflight)
    anonymous = request(requests.Session(), "GET", "/api/auth/sessions", origin=False)
    check("Session listing requires Bearer authentication",
          anonymous is not None and anonymous.status_code == 401, anonymous)

    logout_client = login("postmanB", passwords["postmanB"], "logout")
    if logout_client:
        logout = request(logout_client, "POST", "/api/auth/logout")
        check("Logout revokes the session and clears the cookie",
              logout is not None and logout.status_code == 204
              and "Max-Age=0" in logout.headers.get("Set-Cookie", ""), logout)
        repeated = request(logout_client, "POST", "/api/auth/logout")
        check("Repeated logout is safe", repeated is not None and repeated.status_code == 204, repeated)

    all_client = login("postmanB", passwords["postmanB"], "logout-all")
    if all_client:
        logout_all = request(all_client, "POST", "/api/auth/logout-all")
        check("Logout-all revokes the caller's sessions",
              logout_all is not None and logout_all.status_code == 204, logout_all)
        after = request(all_client, "POST", "/api/auth/refresh")
        check("Logout-all leaves no usable refresh session",
              after is not None and after.status_code == 401, after)


def run():
    try:
        run_flow()
    finally:
        cleanup()


if __name__ == "__main__":
    run()
    print(f"\nPhase 11B API tests: {passed} passed, {failed} failed")
    sys.exit(1 if failed else 0)
