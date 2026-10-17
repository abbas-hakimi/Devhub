"""Real Chrome tests. No credentials, cookies, or network bodies are logged."""
import os
import re
import uuid
from pathlib import Path
from urllib.request import urlopen

import pytest
from selenium import webdriver
from selenium.common.exceptions import WebDriverException
from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait

FRONTEND = os.getenv("DEVHUB_FRONTEND_URL", "http://localhost:3000").rstrip("/")
BACKEND = os.getenv("DEVHUB_BACKEND_URL", "http://localhost:8080").rstrip("/")
ARTIFACTS = Path(__file__).parent / "artifacts"


@pytest.fixture(scope="session")
def frontend_url():
    try:
        with urlopen(FRONTEND, timeout=5) as response:
            assert response.status == 200
    except Exception:
        pytest.skip("Frontend unavailable: start npm run dev at http://localhost:3000")
    return FRONTEND


@pytest.fixture(scope="session")
def backend_ready():
    try:
        with urlopen(BACKEND + "/api/health", timeout=5) as response:
            assert response.status == 200
    except Exception:
        pytest.skip("Spring Boot unavailable: start backend on localhost:8080 with MySQL V14")


@pytest.fixture(scope="session")
def credentials(backend_ready):
    username = os.getenv("DEVHUB_TEST_USERNAME")
    password = os.getenv("DEVHUB_TEST_PASSWORD")
    if not username or not password:
        pytest.skip("Set DEVHUB_TEST_USERNAME and DEVHUB_TEST_PASSWORD for authenticated tests")
    return username, password


@pytest.fixture
def driver(frontend_url, request):
    options = webdriver.ChromeOptions()
    options.add_argument("--headless=new")
    options.add_argument("--window-size=1440,1000")
    options.add_argument("--disable-save-password-bubble")
    options.add_experimental_option("prefs", {"credentials_enable_service": False, "profile.password_manager_enabled": False})
    try:
        # No driver path: Selenium Manager locates Chrome and manages ChromeDriver.
        browser = webdriver.Chrome(options=options)
    except WebDriverException:
        pytest.fail("Chrome startup failed. Install Chrome and allow Selenium Manager driver downloads.", pytrace=False)
    browser.set_script_timeout(30)
    browser.set_page_load_timeout(30)
    request.node.browser = browser
    yield browser
    # Only revoke the cookie session belonging to this fresh browser profile.
    try:
        if browser.current_url.startswith(frontend_url):
            browser.execute_async_script("""
                const done = arguments[arguments.length - 1];
                fetch('/api/auth/logout', {method:'POST', credentials:'include'})
                  .then(r => done(r.status)).catch(() => done(0));
            """)
    finally:
        browser.quit()


@pytest.fixture
def wait(driver):
    return WebDriverWait(driver, 25)


def ready_login(driver, wait, frontend_url):
    driver.get(frontend_url + "/login")
    wait.until(EC.element_to_be_clickable((By.ID, "identifier")))


@pytest.fixture
def logged_in(credentials, driver, wait, frontend_url):
    ready_login(driver, wait, frontend_url)
    username, password = credentials
    driver.find_element(By.ID, "identifier").send_keys(username)
    driver.find_element(By.ID, "password").send_keys(password)
    driver.find_element(By.CSS_SELECTOR, "button[type=submit], .submit-button").click()
    wait.until(EC.visibility_of_element_located((By.CSS_SELECTOR, "#main h1")))
    assert driver.find_elements(By.CSS_SELECTOR, "button[aria-label='Account menu']"), "Login did not open workspace"
    return driver


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    report = outcome.get_result()
    browser = getattr(item, "browser", None)
    if report.failed and browser:
        ARTIFACTS.mkdir(exist_ok=True)
        name = re.sub(r"[^a-zA-Z0-9_-]", "_", item.name)
        # Mask form values and account identifiers before saving a failure image.
        try:
            browser.execute_script("document.querySelectorAll('input,.profile-menu').forEach(e=>e.style.visibility='hidden')")
            browser.save_screenshot(str(ARTIFACTS / (name + ".png")))
        except WebDriverException:
            pass


@pytest.fixture
def unique_registration(backend_ready):
    if os.getenv("DEVHUB_E2E_ALLOW_REGISTRATION") != "1":
        pytest.skip("Registration creates a retained test user; set DEVHUB_E2E_ALLOW_REGISTRATION=1 to opt in")
    name = "e2e_" + uuid.uuid4().hex[:18]
    return name, name + "@example.test", uuid.uuid4().hex + "Aa1!"
