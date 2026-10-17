"""Inspect cookies for their actual request URL, independent of the current SPA path."""
import hashlib
import os
from urllib.parse import urlsplit


def refresh_cookie(driver):
    origin = urlsplit(driver.current_url)
    url = f"{origin.scheme}://{origin.netloc}/api/auth/refresh"
    name = os.getenv("DEVHUB_E2E_REFRESH_COOKIE", "DEVHUB_REFRESH")
    # WebDriver get_cookies/get_cookie are scoped to the current document URL.
    # CDP queries cookies applicable to the auth URL without navigating away,
    # making another refresh request, or exposing credentials to page JavaScript.
    matches = [cookie for cookie in driver.execute_cdp_cmd(
        "Network.getCookies", {"urls": [url]}
    )["cookies"] if cookie["name"] == name and cookie["path"] == "/api/auth"]
    if len(matches) != 1:
        raise AssertionError("Expected exactly one refresh cookie applicable to /api/auth/refresh")
    cookie = matches[0]
    # Keep values out of assertion introspection and diagnostic output. Only a
    # one-way fingerprint is used to compare rotation inside Python memory.
    return {"name": cookie["name"], "path": cookie["path"],
            "httpOnly": cookie.get("httpOnly"), "sameSite": cookie.get("sameSite"),
            "secure": cookie.get("secure"),
            "fingerprint": hashlib.sha256(cookie["value"].encode()).digest()}


def assert_rotated(before, after):
    changed = before["fingerprint"] != after["fingerprint"]
    if not changed:
        raise AssertionError("Refresh cookie did not rotate after authenticated session renewal")
