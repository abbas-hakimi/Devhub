"""Git data is immutable: writes require explicit opt-in to retained disposable data."""
import os
import uuid
import pytest
from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import Select
from interactions import click_unobstructed


@pytest.fixture
def code_repository(request, credentials):
    if os.getenv('DEVHUB_E2E_ALLOW_GIT_DATA') != '1':
        pytest.skip('Set DEVHUB_E2E_ALLOW_GIT_DATA=1 to retain a unique disposable repository with immutable Git history')
    driver = request.getfixturevalue('logged_in')
    wait = request.getfixturevalue('wait')
    frontend = request.getfixturevalue('frontend_url')
    name = 'e2e_code_' + uuid.uuid4().hex[:16]
    driver.get(frontend + '/repositories/new')
    wait.until(EC.element_to_be_clickable((By.ID,'repo-name'))).send_keys(name)
    click_unobstructed(driver,wait,(By.XPATH,"//button[normalize-space()='Create repository']"))
    wait.until(lambda d:d.current_url.rstrip('/').split('/')[-1].isdigit())
    repository_id=driver.current_url.rstrip('/').split('/')[-1]
    try:
        yield driver,wait,frontend,repository_id
    finally:
        # No supported API deletes commits/branches; never weaken FK safeguards.
        request.node.add_report_section('teardown','Retained Git test data',f'Disposable repository {repository_id} ({name}) retained. Immutable Git history has no deletion API; no unrelated data changed.')


def test_code_initialize_commit_browse_branch_history_diff(code_repository):
    d,w,front,rid=code_repository
    d.get(front+'/repositories/'+rid+'/code')
    click_unobstructed(d,w,(By.XPATH,"//button[normalize-space()='Initialize repository']"))
    w.until(EC.alert_is_present()).accept()
    w.until(EC.presence_of_element_located((By.CSS_SELECTOR,"select[aria-label='Current branch']")))
    click_unobstructed(d,w,(By.XPATH,"//button[normalize-space()='Create or update a text file']"))
    d.find_element(By.ID,'file-path').send_keys('src/hello world.txt')
    d.find_element(By.ID,'file-content').send_keys('Hello from isolated Selenium')
    d.find_element(By.ID,'commit-message').send_keys('Add isolated greeting')
    click_unobstructed(d,w,(By.XPATH,"//button[normalize-space()='Commit file']"))
    w.until(EC.alert_is_present()).accept()
    click_unobstructed(d,w,(By.XPATH,"//button[normalize-space()='src']"))
    click_unobstructed(d,w,(By.XPATH,"//button[normalize-space()='hello world.txt']"))
    w.until(EC.text_to_be_present_in_element((By.CSS_SELECTOR,'.code-source'),'Hello from isolated Selenium'))
    d.refresh()
    w.until(EC.visibility_of_element_located((By.CSS_SELECTOR,'.code-source')))
    d.find_element(By.ID,'branch-name').send_keys('feature/selenium')
    click_unobstructed(d,w,(By.XPATH,"//button[normalize-space()='Create branch']"))
    w.until(lambda browser:len(browser.find_elements(By.CSS_SELECTOR,"select[aria-label='Current branch'] option"))==2)
    Select(d.find_element(By.CSS_SELECTOR,"select[aria-label='Current branch']")).select_by_value('feature/selenium')
    w.until(EC.url_contains('feature'))
    click_unobstructed(d,w,(By.XPATH,"//button[normalize-space()='Commit history']"))
    click_unobstructed(d,w,(By.XPATH,"//button[normalize-space()='Add isolated greeting']"))
    w.until(EC.text_to_be_present_in_element((By.CSS_SELECTOR,'.repository-section'),'Parent:'))
    click_unobstructed(d,w,(By.XPATH,"//button[normalize-space()='Compare branches']"))
    w.until(EC.text_to_be_present_in_element((By.ID,'main'),'No changes between these branches.'))


def test_code_missing_repository(logged_in,wait,frontend_url):
    logged_in.get(frontend_url+'/repositories/9223372036854775807/code')
    wait.until(EC.visibility_of_element_located((By.CSS_SELECTOR,'[role=alert]')))
