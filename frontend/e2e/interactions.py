"""Native clicks after explicit viewport positioning and browser hit testing."""
from selenium.common.exceptions import ElementClickInterceptedException, StaleElementReferenceException, TimeoutException


def click_unobstructed(driver, wait, locator):
    last_blocker = {'description': 'element not yet ready'}

    def attempt(browser):
        try:
            element = browser.find_element(*locator)
            if not element.is_displayed() or not element.is_enabled():
                return False
            browser.execute_script("arguments[0].scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});", element)
            hit = browser.execute_script(r"""
              const e=arguments[0],r=e.getBoundingClientRect();
              const x=r.left+r.width/2,y=r.top+r.height/2;
              const top=document.elementFromPoint(x,y);
              return {clear:x>=0&&x<innerWidth&&y>=0&&y<innerHeight&&!!top&&(top===e||e.contains(top)),
                blocker:top ? top.tagName.toLowerCase()+'.'+String(top.className).replace(/\s+/g,'.') : 'outside viewport'};
            """, element)
            last_blocker['description'] = hit['blocker']
            if not hit['clear']:
                return False
            element.click()  # WebDriver native click; never a JavaScript click.
            return True
        except (ElementClickInterceptedException, StaleElementReferenceException):
            return False

    try:
        wait.until(attempt)
    except TimeoutException:
        raise TimeoutException('Native click remained obstructed; last hit target: ' + last_blocker['description']) from None
