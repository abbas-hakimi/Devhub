from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC
from conftest import ready_login


def test_theme_toggle_and_persistence(driver, wait, frontend_url):
    ready_login(driver, wait, frontend_url)
    before = driver.find_element(By.TAG_NAME, "html").get_attribute("data-bs-theme")
    driver.find_element(By.CSS_SELECTOR, "button[aria-label^='Switch to']").click()
    after = driver.find_element(By.TAG_NAME, "html").get_attribute("data-bs-theme")
    assert before != after
    driver.refresh()
    wait.until(EC.element_to_be_clickable((By.ID, "identifier")))
    assert driver.find_element(By.TAG_NAME, "html").get_attribute("data-bs-theme") == after
