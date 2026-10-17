"""All mutations are restricted to a repository created by this test."""
import uuid
import pytest
from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import Select
from interactions import click_unobstructed


@pytest.fixture
def disposable_repository(logged_in, wait, frontend_url, credentials):
    from cleanup import cleanup_repository
    name = 'e2e_repo_' + uuid.uuid4().hex[:16]
    repository_id = None
    try:
        logged_in.get(frontend_url + '/repositories/new')
        wait.until(EC.element_to_be_clickable((By.ID, 'repo-name'))).send_keys(name)
        logged_in.find_element(By.ID, 'repo-description').send_keys('Disposable Phase 12B test')
        click_unobstructed(logged_in, wait, (By.XPATH, "//button[normalize-space()='Create repository']"))
        wait.until(lambda d: '/repositories/' in d.current_url and d.current_url.rstrip('/').split('/')[-1].isdigit())
        repository_id = logged_in.current_url.rstrip('/').split('/')[-1]
        yield logged_in, name, repository_id
    finally:
        # Independent HTTP cleanup still works if Chrome exits or setup fails
        # after POST succeeded but before the browser captured the created ID.
        cleanup_repository(credentials, name, repository_id)

def test_create_open_edit_reload_search_and_delete(disposable_repository, wait, frontend_url):
    driver, name, repository_id = disposable_repository
    assert name in wait.until(EC.visibility_of_element_located((By.CSS_SELECTOR, '#main h1'))).text
    click_unobstructed(driver, wait, (By.CSS_SELECTOR, "nav[aria-label='Repository navigation'] a[href$='/settings']"))
    field = wait.until(EC.element_to_be_clickable((By.ID, 'repo-name')))
    field.clear(); field.send_keys(name + '_edited')
    driver.find_element(By.ID, 'repo-description').clear()
    driver.find_element(By.ID, 'repo-description').send_keys('Searchable unique description ' + name)
    click_unobstructed(driver, wait, (By.XPATH, "//button[normalize-space()='Save changes']"))
    wait.until(EC.text_to_be_present_in_element((By.CSS_SELECTOR, '.alert-success'), 'settings saved'))
    driver.refresh()
    wait.until(EC.text_to_be_present_in_element((By.CSS_SELECTOR, '#main h1'), name + '_edited'))
    driver.get(frontend_url + '/repositories')
    search = wait.until(EC.element_to_be_clickable((By.ID, 'repository-search')))
    search.send_keys(name)
    Select(driver.find_element(By.ID, 'repository-ownership')).select_by_value('OWNED')
    Select(driver.find_element(By.ID, 'repository-visibility')).select_by_value('PRIVATE')
    Select(driver.find_element(By.ID, 'repository-sort')).select_by_value('name')
    wait.until(EC.presence_of_element_located((By.CSS_SELECTOR, '[data-testid=repository-card]')))
    cards = driver.find_elements(By.CSS_SELECTOR, '[data-testid=repository-card]')
    assert len(cards) == 1 and name in cards[0].text
    click_unobstructed(driver, wait, (By.CSS_SELECTOR, f"[data-testid=repository-card] a[href='/repositories/{repository_id}']"))
    wait.until(EC.url_to_be(frontend_url + '/repositories/' + repository_id))
    click_unobstructed(driver, wait, (By.CSS_SELECTOR, "nav[aria-label='Repository navigation'] a[href$='/settings']"))
    click_unobstructed(driver, wait, (By.XPATH, "//button[normalize-space()='Delete repository']"))
    wait.until(EC.element_to_be_clickable((By.ID, 'delete-confirmation'))).send_keys(name + '_edited')
    click_unobstructed(driver, wait, (By.XPATH, "//dialog//button[normalize-space()='Permanently delete']"))
    wait.until(EC.url_to_be(frontend_url + '/repositories'))
    # Independent API teardown also handles partial failures and an already deleted resource.


def test_missing_repository_error(logged_in, wait, frontend_url):
    logged_in.get(frontend_url + '/repositories/9223372036854775807')
    assert 'not found or you do not have access' in wait.until(EC.visibility_of_element_located((By.CSS_SELECTOR, '[role=alert]'))).text
