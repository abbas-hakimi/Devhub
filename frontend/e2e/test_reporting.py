"""Regression: reporting must preserve failures when the driver transport dies."""
from types import SimpleNamespace
import pytest
from conftest import pytest_runtest_makereport


@pytest.mark.parametrize('failure', [ConnectionRefusedError, OSError, RuntimeError])
def test_reporting_survives_disconnected_browser(failure):
    class DeadBrowser:
        def execute_script(self, *_):
            raise failure('disconnected')

        def save_screenshot(self, *_):
            raise AssertionError('Must not capture an unmasked screenshot')

    report = SimpleNamespace(failed=True, when='call', sections=[], longrepr='original test failure')
    item = SimpleNamespace(browser=DeadBrowser(), name='reporting_regression')
    hook = pytest_runtest_makereport(item, None)
    next(hook)
    with pytest.raises(StopIteration):
        hook.send(SimpleNamespace(get_result=lambda: report))
    assert report.longrepr == 'original test failure'
    assert report.failed is True
    assert report.sections[0][0] == 'Screenshot'
