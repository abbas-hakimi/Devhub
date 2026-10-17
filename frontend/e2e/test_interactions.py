"""Repeat the real native-click positioning without credentials or API writes."""
import pytest
from selenium.webdriver.common.by import By
from interactions import click_unobstructed


@pytest.mark.parametrize('width,height', [(1440,1000),(390,844)])
def test_native_click_below_viewport_repeatedly(driver, wait, width, height):
    driver.set_window_size(width,height)
    driver.get('data:text/html,<html><body><div style="height:1500px"></div><button id="save" onclick="this.dataset.count=String(Number(this.dataset.count||0)+1)">Save changes</button><div style="height:1000px"></div></body></html>')
    for count in range(1,4):
        driver.execute_script('window.scrollTo(0,0)')
        click_unobstructed(driver,wait,(By.ID,'save'))
        assert driver.find_element(By.ID,'save').get_attribute('data-count') == str(count)
