from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC


def test_dashboard_user(logged_in, credentials):
    logged_in.find_element(By.CSS_SELECTOR, "button[aria-label='Account menu']").click()
    username = logged_in.find_element(By.CSS_SELECTOR, ".profile-menu small").text
    assert username.startswith("@") and len(username) > 1, "Authenticated username missing"
    # Identifier can be an email, so verify nonempty server-provided username.


def test_real_repository_list_and_navigation(logged_in, wait):
    logged_in.find_element(By.LINK_TEXT, "Go to repositories").click()
    wait.until(EC.text_to_be_present_in_element((By.CSS_SELECTOR, "#main h1"), "Repositories"))
    wait.until(lambda d: d.find_elements(By.CSS_SELECTOR, ".repository-list,.empty-panel,[role=alert]"))
    assert not logged_in.find_elements(By.CSS_SELECTOR, "[role=alert]"), "Real repository API returned an error"
    cards = logged_in.find_elements(By.CSS_SELECTOR, ".repository-card")
    if cards:
        assert all(card.find_element(By.TAG_NAME, "h2").text.strip() for card in cards)
    else:
        assert "You don’t own any repositories yet" in logged_in.find_element(By.CSS_SELECTOR, ".empty-panel").text


def test_loading_screen_during_restoration(logged_in, wait):
    # CDP adds network latency while leaving real backend responses untouched.
    logged_in.execute_cdp_cmd("Network.enable", {})
    logged_in.execute_cdp_cmd("Network.emulateNetworkConditions", {"offline": False, "latency": 700, "downloadThroughput": 1000000, "uploadThroughput": 1000000})
    try:
        logged_in.execute_script("window.location.reload()")
        wait.until(EC.visibility_of_element_located((By.CSS_SELECTOR, ".loading-screen[role=status]")))
        wait.until(EC.visibility_of_element_located((By.CSS_SELECTOR, "#main h1")))
    finally:
        logged_in.execute_cdp_cmd("Network.emulateNetworkConditions", {"offline": False, "latency": 0, "downloadThroughput": -1, "uploadThroughput": -1})
