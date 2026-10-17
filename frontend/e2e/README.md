# Real-browser Phase 12A verification

Python 3.10+, pytest, Selenium 4, installed Chrome, and Selenium Manager (automatic driver discovery/download). Every test uses a new headless Chrome profile and explicit WebDriverWait conditions. No repository data is written. No credentials or token values are printed. Failed tests save masked screenshots under `e2e/artifacts/` (ignored by Git).

## Start services

In a backend terminal, provide the existing local `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `JWT_SECRET` environment configuration. Do not put these values in frontend configuration. MySQL must have V14 applied.

```powershell
cd C:\Users\hakim\OneDrive\Desktop\version-control\backend
.\mvnw.cmd spring-boot:run
```

In a second terminal:

```powershell
cd C:\Users\hakim\OneDrive\Desktop\version-control\frontend
npm install
npm run dev
```

Use `http://localhost:3000`, with `/api` proxied to port 8080. Keep backend `FRONTEND_ORIGINS=http://localhost:3000`; local HTTP uses `AUTH_COOKIE_SECURE=false`. Do not disable Origin checks or HttpOnly. Alternate URLs use `DEVHUB_FRONTEND_URL` and `DEVHUB_BACKEND_URL`, but must remain consistent with the actual proxy/origin configuration.

## Install and run from project root

```powershell
cd C:\Users\hakim\OneDrive\Desktop\version-control
python -m venv frontend/.venv
.\frontend\.venv\Scripts\Activate.ps1
python -m pip install -r frontend/e2e/requirements.txt
$env:DEVHUB_TEST_USERNAME = Read-Host 'Existing test username or email'
$testCredential = Read-Host 'Test password' -AsSecureString
$env:DEVHUB_TEST_PASSWORD = [System.Net.NetworkCredential]::new('', $testCredential).Password
python -m pytest frontend/e2e -v
Remove-Item Env:DEVHUB_TEST_PASSWORD
```

Do not pass passwords on the command line. Authenticated tests skip clearly when backend health or credentials are unavailable. Frontend absence skips all browser tests. Chrome/driver startup failure is a failure, not a passing test. Selenium Manager may need network access on its first run. Missing/invalid credentials must be resolved before claiming full integration verification. Run sequentially (no pytest-xdist).

Each browser's own cookie session is revoked through POST `/api/auth/logout` during teardown; logout-all is never used, and other account sessions are untouched. Revoked session history remains under backend retention policy. No DB cleanup is executed. Screenshots mask inputs/profile menu, but may still contain repository names; treat artifacts as local private data.

Successful registration is skipped by default because there is no supported user-deletion endpoint. To explicitly allow one unique `e2e_*@example.test` account to remain per run, set `DEVHUB_E2E_ALLOW_REGISTRATION=1`. Generated credentials stay only in test memory and the new user is never used to alter repositories.

The expiry test uses a real short-lived JWT on a **dedicated local test backend**. Start that backend with `ACCESS_TOKEN_TTL=PT5S`, then set `DEVHUB_E2E_ACCESS_TTL_SECONDS=5` in the pytest terminal. It uses a deadline-based WebDriverWait (no 15-minute wait, no fake responses, no signing key) before navigating to repositories and verifying cookie rotation. Otherwise this one test skips. Restore normal backend token lifetime afterward.

Repository-list tests assert a successful real UI result: actual cards or the documented empty state. They cannot force an existing account to have zero repositories without modifying its data. Loading-screen coverage uses Chrome network latency while retaining genuine backend responses. HttpOnly is checked through WebDriver metadata and a synchronous JavaScript visibility check after top-level navigation to `/api/auth/__cookie_visibility_probe`, inside the cookie's scoped path. This harmless unknown GET returns an error document and does not rotate or revoke the session. No iframe or asynchronous callback is involved.

Cookie inspection uses Chrome CDP `Network.getCookies` with the explicit `/api/auth/refresh` URL. WebDriver `get_cookies()` and `get_cookie()` use the current document's URL and can omit `/api/auth` cookies while the SPA is at `/` or another workspace route. The helper retains only cookie metadata and a one-way fingerprint for rotation comparison; values are never logged. It does not navigate to an auth endpoint or trigger a second refresh. Override the default cookie name only if the backend config differs, using `DEVHUB_E2E_REFRESH_COOKIE`.

Frontend regression commands:

```powershell
cd frontend
npm run build
npm run lint
npm test -- --run
```

## Phase 12B workflows

`test_repositories.py` creates a uniquely named private repository through the UI, opens and edits it, reloads to verify session persistence, searches/filters/sorts, and confirms deletion in fixture teardown. It never initializes Git or creates dependent features, so the existing deletion safeguards remain intact. A failed cleanup is a test error rather than a silent success. Existing account credentials use the same environment variables as Phase 12A. Missing credentials/server/browser prerequisites are reported by existing fixtures. Star E2E is unavailable because there is no star REST endpoint. A missing repository route is also checked. Component tests cover reader denial, conflict handling, mutation payloads, native confirmation, lists, loading and empty states.

## Phase 12B reporting and cleanup hardening

Failure screenshots are best-effort: filesystem, Selenium, and driver-transport exceptions cannot replace the original report. Inputs, textareas, and profile details are masked only during capture, then restored. Dead-browser logout/quit failures are recorded as cleanup diagnostics. Original browser-operation failures remain ordinary failed tests. Reporting regression tests simulate ConnectionRefusedError/OSError without Chrome.

The disposable repository fixture now registers cleanup before browser creation submission. Independent authenticated API cleanup uses only this run's unique original/edited name, authenticated owner ID, and tracked repository ID when available. It can recover after Chrome exits or creation succeeds before redirect capture. Cleanup failure raises a clear teardown error; it never broad-deletes repositories. The successful workflow still confirms deletion through the UI, with API cleanup as fallback. The separate cleanup login session is logged out afterward.

The repository Save action uses a native-click helper: center-scroll, verify the target is enabled/visible and topmost at its center, then WebDriver click. Intercepted/stale races retry under WebDriverWait; persistent obstructions fail with a sanitized hit-target tag/class. No JavaScript click is used. `test_interactions.py` repeats off-screen native clicks on desktop/mobile without credentials; authenticated workflow remains a separate test.
