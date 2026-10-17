# DevHub frontend · Phase 12B

React/JSX + Vite, Bootstrap 5, React Router, Axios, TanStack Query, React Hook Form, Zod, and Lucide. Requires Node 22.12+ (Node 24 is also supported).

## Run locally

Start the existing backend on port 8080 using its documented environment configuration (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`). MySQL must already have Flyway V14 applied. From `backend`, run ` .\mvnw.cmd spring-boot:run`. No backend code or migrations were changed for this phase.

From the project root:

```powershell
cd frontend
npm install
npm run dev
```

Open **http://localhost:3000**. Vite binds to localhost, requires port 3000, and proxies `/api` to `http://localhost:8080` without rewriting the browser Origin. This matches the backend default trusted origin `http://localhost:3000`. Use `localhost`, not `127.0.0.1`. Do not use port 5173 unless the backend trusted origins are explicitly configured for it. No cookie security settings need to be weakened.

Copy `.env.example` to `.env.local` only when overriding defaults. `VITE_API_BASE_URL` defaults to `/api`; `DEVHUB_PROXY_TARGET` configures the development proxy backend. Frontend environment values are public: never add JWT signing keys, database credentials, passwords, or tokens. A direct cross-origin API URL requires matching backend CORS/origin configuration and suitable HTTPS/cookie settings. Production should reverse-proxy `/api` on the frontend's origin, use HTTPS/Secure cookies, and configure that exact frontend origin. `npm run preview` serves static output only and does not provide an API proxy.

## Authentication

- Login posts `{identifier,password}` and receives the existing `accessToken`, `tokenType`, `expiresInSeconds`, and `user` response. `/auth/me` loads the current profile.
- Registration matches username (3–50), email (max 254), password (8–72), and optional displayName (max 100). Registration routes to sign-in: the backend register endpoint does not issue a refresh cookie, so a login establishes the durable session.
- Access JWTs live exclusively in the Axios session module's memory. Neither localStorage nor sessionStorage stores credentials. Only the selected theme is stored locally.
- On startup, one cookie-aware `/auth/refresh` request restores the session, followed by `/auth/me`. The route gate shows a restoration screen first.
- Refresh/logout requests use `withCredentials`. JavaScript never reads the HttpOnly refresh cookie. Only requests marked `authRequired` inject Bearer credentials and retry a 401; public errors and 403 responses never cause refresh. Retries are bounded to one. Parallel refresh requests share a promise; late 401 responses use the newly obtained JWT.
- Logout clears local state/query data and revokes the cookie session. A generation counter prevents an earlier in-flight refresh from signing the user back in after logout. If server logout cannot be confirmed, the login page explains it. Stateless access JWTs issued earlier remain valid until expiry, as documented by the backend.
- Refresh failure returns to sign-in. Permission errors remain permission errors. The `/forbidden` page is available for later permission-aware screens.

## Structure and scope

`components/`: UI primitives and error boundary; `layouts/`: responsive workspace shell; `features/auth/`: forms and route protection; `features/dashboard/`: actual user welcome and navigation; `services/`: central transport and repository service; `context/`: auth/theme; `pages/`: repository list, placeholders, forbidden/404; `styles/`: shared navy/violet and off-white design tokens. Hooks/utilities can be added as reusable needs arise.

Phase 12B implements dashboard statistics from GET /repositories/mine (owned plus accepted collaboration repositories), search by name/description, visibility/ownership filters, updated/name/created sorting, and public discovery via GET /repositories. Filtering is client-side over the existing array APIs; no polling or per-card requests are used.

Routes: `/` dashboard; `/repositories` accessible repositories; `/explore` public repositories; `/repositories/new` creation; `/repositories/:repositoryId` overview; `/repositories/:repositoryId/settings` management. Stable numeric IDs survive renames. Create/update send only name (required, max 100), description (max 10,000), and PUBLIC/PRIVATE visibility. Owner is derived by the backend. Settings permit owners and confirmed accepted MAINTAIN collaborators; deletion is owner-only and requires typing the repository name in a native accessible modal. Server 409 dependency errors stay visible and never imply deletion succeeded. Query caches are updated/invalidated after confirmed mutations.

Overview loads actual repository metadata, timestamps, branches/head summaries, and collaborator records where owner/MAINTAIN access permits. Main is identified only when present; there is no configurable default-branch field. No total commit-count or star REST API exists, so neither is fabricated. Code, issues, PRs, discussions, projects, releases are explicitly upcoming repository sections. AI is planned only. Search in the global topbar remains unavailable; repository search is functional.

Repository components live under `features/repositories`; APIs remain in `services/repositories.js`. No backend production changes or migrations were needed. Existing authentication and theme behavior is preserved. Array listing may require server pagination in a later scalability phase.

## Verification

```powershell
npm run build
npm test -- --run
npm run lint
```

Unit tests mock HTTP at the Axios adapter boundary and cover contract preservation, refresh/retry/expiry/logout, deduplication, forms, route restoration/redirects, navigation, and theme preference. These do not prove live MySQL or browser cookie interoperability; verify login → reload → logout with your running backend before committing. Use one tab for this phase: refresh coordination is within a tab; cross-tab rotation coordination is a remaining limitation. Google Fonts are optional; system fonts are the fallback if unavailable.
