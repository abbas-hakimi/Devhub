import pytest
from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC


@pytest.mark.parametrize("path,title", [("explore", "Explore"), ("issues", "Issues"), ("pull-requests", "Pull requests"), ("projects", "Projects"), ("discussions", "Discussions"), ("notifications", "Notifications"), ("settings", "Settings")])
def test_sidebar_placeholders(logged_in, wait, path, title):
    logged_in.find_element(By.CSS_SELECTOR, f"nav[aria-label='Main navigation'] a[href='/{path}']").click()
    wait.until(EC.text_to_be_present_in_element((By.CSS_SELECTOR, "#main h1"), title))
    if path == 'explore':
        # Explore is implemented in Phase 12B; retain the navigation assertion
        # and verify its real public-repository browser instead of a placeholder.
        wait.until(EC.visibility_of_element_located((By.ID, 'repository-search')))
    else:
        assert "COMING IN A LATER PHASE" in logged_in.find_element(By.CSS_SELECTOR, ".empty-panel").text


def test_mobile_navigation(logged_in, wait):
    logged_in.set_window_size(390, 844)
    opener = wait.until(EC.element_to_be_clickable((By.CSS_SELECTOR, "button[aria-label='Open navigation']")))
    opener.click()
    wait.until(lambda d: "mobile-open" in d.find_element(By.ID, "workspace-navigation").get_attribute("class"))
    logged_in.find_element(By.CSS_SELECTOR, "nav[aria-label='Main navigation'] a[href='/projects']").click()
    wait.until(lambda d: "mobile-open" not in d.find_element(By.ID, "workspace-navigation").get_attribute("class"))
    assert logged_in.find_element(By.CSS_SELECTOR, "#main h1").text == "Projects"


def test_unknown_route(driver, wait, frontend_url):
    driver.get(frontend_url + "/unknown-e2e-route")
    wait.until(EC.visibility_of_element_located((By.CLASS_NAME, "error-number")))
    assert driver.find_element(By.CLASS_NAME, "error-number").text == "404"
