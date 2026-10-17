from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC
from conftest import ready_login
from cookie_helpers import refresh_cookie, assert_rotated


def test_valid_login(logged_in):
    assert "Welcome back," in logged_in.find_element(By.CSS_SELECTOR, "#main h1").text


def test_invalid_credentials(backend_ready, driver, wait, frontend_url):
    ready_login(driver, wait, frontend_url)
    driver.find_element(By.ID, "identifier").send_keys("nonexistent_e2e_account")
    driver.find_element(By.ID, "password").send_keys("invalid-credentials-only")
    driver.find_element(By.CSS_SELECTOR, ".submit-button").click()
    alert = wait.until(EC.visibility_of_element_located((By.CSS_SELECTOR, "[role=alert]")))
    assert "incorrect" in alert.text, "Backend invalid-login error was not shown"


def test_protected_route_redirect(driver, wait, frontend_url):
    driver.get(frontend_url + "/projects")
    wait.until(EC.visibility_of_element_located((By.ID, "identifier")))
    assert "/login" in driver.current_url


def test_logout(logged_in, wait):
    logged_in.find_element(By.CSS_SELECTOR, "button[aria-label='Account menu']").click()
    logged_in.find_element(By.XPATH, "//button[contains(.,'Sign out')]").click()
    wait.until(EC.visibility_of_element_located((By.ID, "identifier")))
    assert not logged_in.find_elements(By.ID, "main")


def test_reload_restores_and_rotates_session(logged_in, wait):
    before = refresh_cookie(logged_in)
    logged_in.refresh()
    wait.until(EC.visibility_of_element_located((By.CSS_SELECTOR, "#main h1")))
    after = refresh_cookie(logged_in)
    assert_rotated(before, after)


def test_registration_validation(driver, wait, frontend_url):
    driver.get(frontend_url + "/register")
    wait.until(EC.element_to_be_clickable((By.ID, "username")))
    driver.find_element(By.CSS_SELECTOR, ".submit-button").click()
    wait.until(EC.visibility_of_element_located((By.ID, "username-error")))
    assert driver.find_element(By.ID, "password-error").text == "Use at least 8 characters."


def test_successful_registration(unique_registration, driver, wait, frontend_url):
    username, email, password = unique_registration
    driver.get(frontend_url + "/register")
    wait.until(EC.element_to_be_clickable((By.ID, "username")))
    for name, value in (("username", username), ("email", email), ("password", password)):
        driver.find_element(By.ID, name).send_keys(value)
    driver.find_element(By.CSS_SELECTOR, ".submit-button").click()
    wait.until(EC.visibility_of_element_located((By.ID, "identifier")))
    assert "Account created" in driver.find_element(By.CSS_SELECTOR, ".alert-success").text


def test_access_token_renewal_after_real_expiry(logged_in, wait):
    import os
    import pytest
    seconds = os.getenv("DEVHUB_E2E_ACCESS_TTL_SECONDS")
    if not seconds or not seconds.isdigit() or not 1 <= int(seconds) <= 30:
        pytest.skip("Use a dedicated backend with ACCESS_TOKEN_TTL=PT5S and DEVHUB_E2E_ACCESS_TTL_SECONDS=5")
    # Real expiry, not mocked 401s or forged tokens. Deadline is the condition
    # under test; polling uses WebDriverWait rather than arbitrary sleeps.
    import time
    deadline = time.monotonic() + int(seconds) + 1
    WebWait = type(wait)
    WebWait(logged_in, int(seconds) + 5).until(lambda _: time.monotonic() >= deadline)
    before = refresh_cookie(logged_in)
    logged_in.find_element(By.CSS_SELECTOR, "nav[aria-label='Main navigation'] a[href='/repositories']").click()
    wait.until(lambda d: d.find_elements(By.CSS_SELECTOR, ".repository-list,.empty-panel,[role=alert]"))
    assert not logged_in.find_elements(By.CSS_SELECTOR, "[role=alert]"), "Repository request failed after expiry"
    assert_rotated(before, refresh_cookie(logged_in))
