from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC
from urllib.parse import urlsplit
from cookie_helpers import refresh_cookie


def test_credentials_absent_from_web_storage(logged_in):
    # Return only a boolean: never serialize tokens into test output.
    safe = logged_in.execute_script(r"""
      return [localStorage,sessionStorage].every(storage => Object.entries(storage).every(([k,v]) =>
        !/token|jwt|password|authorization|session/i.test(k) && !/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\./.test(v)));
    """)
    assert safe, "Sensitive credential material was found in browser storage"


def test_refresh_cookie_is_httponly_and_not_javascript_visible(logged_in):
    cookie = refresh_cookie(logged_in)
    assert cookie["path"] == "/api/auth", "Refresh cookie must retain its authentication-only path"
    assert cookie.get("httpOnly") is True, "Refresh cookie must be HttpOnly"
    assert cookie.get("sameSite") == "Lax", "Expected SameSite=Lax for local development"
    if urlsplit(logged_in.current_url).scheme == "http":
        assert cookie.get("secure") is False, "Local HTTP configuration should not require a Secure cookie"
    # Navigate the top-level document inside the cookie path. This harmless
    # unknown GET returns an error document, never rotates the session, and
    # does not depend on an iframe callback or authenticated JSON endpoint.
    origin = urlsplit(logged_in.current_url)
    scoped_url = f"{origin.scheme}://{origin.netloc}/api/auth/__cookie_visibility_probe"
    logged_in.get(scoped_url)
    assert urlsplit(logged_in.current_url).path.startswith('/api/auth/'), "Cookie check left the scoped path"
    hidden = logged_in.execute_script("return !document.cookie.split(';').some(v => v.trim().startsWith(arguments[0] + '='));", cookie["name"])
    assert hidden, "Refresh cookie was exposed to JavaScript at its scoped path"


def test_protected_page_after_logout(logged_in, wait, frontend_url):
    logged_in.find_element(By.CSS_SELECTOR, "button[aria-label='Account menu']").click()
    logged_in.find_element(By.XPATH, "//button[contains(.,'Sign out')]").click()
    wait.until(EC.visibility_of_element_located((By.ID, "identifier")))
    logged_in.get(frontend_url + "/repositories")
    wait.until(EC.visibility_of_element_located((By.ID, "identifier")))
    assert "/login" in logged_in.current_url
