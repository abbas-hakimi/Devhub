# Rebuild Specification

> Scope: this documents checked-in source from the original backend-main and frontend-main. Current behavior and proposed design are deliberately separated. This is a source inspection, not runtime verification.

## 1. Project overview

The root README describes a MERN-based GitHub replica with custom version control. The source contains an Express backend, React/Vite frontend, MongoDB access through both Mongoose and the MongoDB driver, and a separate yargs command-line prototype that copies local files into commit directories and transfers those directories with AWS S3.

Web code includes signup/login, profile retrieval/update/delete, repository CRUD/listing/visibility handlers, and issue handlers. The frontend currently renders login, signup, dashboard and profile routes. The Git-like commands are local CLI commands, not REST endpoints. No frontend issue UI or Git command integration exists.

Frontend requests are hard-coded to http://localhost:3002. Express listens on PORT or 3000. No proxy or port configuration is present in inspected files. Express mounts routes at /. Socket.IO allows wildcard CORS and has a joinRoom event, but the frontend does not use it.

## 2. Current project structure

Root:
- README.md: one-line project description.
- project-all-code.txt: aggregate code artifact; source files remain the executable source of truth.
- backend-main/: backend.
- frontend-main/: frontend.

Backend:
- package.json/package-lock.json: dependencies and start script node index.js start.
- index.js: yargs start/init/add/commit/push/pull/revert commands; Express, Mongoose, Socket.IO server.
- routes/main.router.js: mounts user, repo and issue routers, GET / welcome response.
- routes/user.router.js, repo.router.js, issue.router.js: REST declarations.
- controllers/userController.js: direct MongoDB-driver authentication/profile/user operations.
- controllers/repoController.js: Mongoose repository operations.
- controllers/issueController.js: Mongoose issue handlers.
- controllers/init.js, add.js, commit.js, push.js, pull.js, revert.js: local filesystem/S3 CLI prototype.
- models/userModel.js, repoModel.js, issueModel.js: Mongoose schemas. User controller bypasses userModel.
- middleware/authMiddleware.js and authorizeMiddleware.js: empty files, unused.
- config/aws-config.js: AWS SDK v2 S3 client, ap-south-1 region, placeholder bucket insert_bucket_name.
- commit.json: sample metadata.

Frontend:
- package.json/package-lock.json: React 18, Vite 5, Router 6, Axios, Primer, heat-map dependencies; dev/build/lint/test/preview scripts.
- index.html: Vite document and Vite + React title.
- src/main.jsx: renders ProjectRoutes under AuthProvider and BrowserRouter.
- src/App.jsx/App.css: unused default Vite counter; App is not mounted.
- src/Routes.jsx: route definitions and localStorage redirects.
- src/authContext.jsx: in-memory current user ID restored from localStorage.
- src/components/auth/Login.jsx and Signup.jsx: credential forms and Axios calls.
- src/components/dashboard/Dashboard.jsx: repository fetch/filter, suggested list and static events.
- src/components/user/Profile.jsx: profile fetch, static follow data and logout.
- src/components/user/HeatMap.jsx: generated random January 2001 activity.
- src/components/Navbar.jsx and CSS: navigation.
- src/assets and public/vite.svg: assets.

## 3. Backend analysis

Server: startServer installs JSON parsing and wildcard-origin CORS, calls mongoose.connect(MONGODB_URI), mounts routes, and listens on PORT or 3000. Connection errors are logged but do not prevent listen. User controller separately lazily connects a MongoClient to the same URI and hard-coded githubclone database. No environment example, global error handler, common validation layer or auth route attachment is present.

### HTTP routes

All routes are mounted at /. Auth requirement below means middleware currently installed; every listed route is unauthenticated.

| Method and endpoint | Purpose, inputs | Response, persistence, validation |
|---|---|---|
| GET / | Welcome | Plain text Welcome! |
| GET /allUsers | Fetch all users | Returns raw user collection documents without projection, potentially including password hashes. |
| POST /signup | Body username, email, password | Checks username only; duplicate returns 400 {message}. bcryptjs salt cost 10, inserts user with empty repositories/followedUsers/starRepos arrays, returns {token,userId}. Uses result.insertId after insertOne; modern driver result uses insertedId, so identity response is suspect. No required-field/email validation in handler. |
| POST /login | Body email/password | Finds by email; missing/mismatch -> 400 {message:"Invalid credentials!"}; bcrypt compare; success returns {token,userId}, JWT claim id and one-hour expiry. |
| GET /userProfile/:id | User ID path | Mongo ObjectId lookup; 404 if absent, otherwise full document. Invalid ID becomes caught 500. |
| PUT /updateProfile/:id | ID path; body email and optional password | Updates email, optionally hashes password, returns updated doc or 404. No email validation/uniqueness check. |
| DELETE /deleteProfile/:id | ID path | Deletes user; checks result.deleteCount, which is not the modern driver deletion-count field, so deletion result reporting is suspect. No related-record cleanup. |
| POST /repo/create | Body owner, name, issues, content, description, visibility | Requires name and valid Mongoose ObjectId owner. Saves Repository; 201 {message,repositoryID}; errors including duplicates become generic 500. Owner is client supplied. |
| GET /repo/all | None | Returns every repository populated with owner and issues; no visibility filtering. |
| GET /repo/:id | ID path | Mongoose find by _id with populated owner/issues; returns array, not single object. |
| GET /repo/name/:name | Name path | Exact-name find, populated owner/issues; returns array. |
| GET /repo/user/:userID | User ID path | Finds owner match; 404 when empty, otherwise {message,repositories}. Identity is not tied to token. |
| PUT /repo/update/:id | ID path; body content/description | Appends content value as one array member, replaces description; returns {message,repository}. No auth/ownership check. |
| PATCH /repo/toggle/:id | ID path | Negates visibility and returns updated repository; no auth/ownership check. |
| DELETE /repo/delete/:id | ID path | Deletes repository; 404 if missing, otherwise message. Related references are not synchronized. |
| POST /issue/create | Handler reads title/description body and req.params.id | Route has no :id although handler needs repository id. Schema requires repository; usual request fails persistence and returns 500. |
| PUT /issue/update/:id | Issue ID; title/description/status body | Loads issue, assigns all three fields, saves and returns JSON; 404 if missing. Schema enum is open/closed. |
| DELETE /issue/delete/:id | Issue ID | findByIdAndDelete is not awaited; promise truthiness defeats missing-record check and success can be returned before deletion completes. |
| GET /issue/all | Handler reads req.params.id | Route provides no id; query is not awaited, so response is a query object rather than resolved issue array. |
| GET /issue/:id | Issue ID | Finds and returns issue or 404. |

Issue controller imports Repository/User but does not use them. Most errors are logged and converted to generic 500.

### CLI commands, not HTTP APIs

- init creates .apnaGit and .apnaGit/commits, writes config.json with process.env.S3_BUCKET.
- add <file> creates staging and copies source to staging using basename only.
- commit <message> creates UUID-named commits directory, copies all staged files, writes commit.json with message and ISO date.
- push uploads each file in each local commit directory to S3 key commits/<commitDir>/<filename>.
- pull lists S3 objects with prefix commits/, downloads each, writes under .apnaGit/<key>.
- revert <commitID> copies every file in that commit directory into the parent of .apnaGit (working directory); it does not delete extra files or move a head pointer.
Errors are logged and swallowed. Commands do not return structured results and operate on process working directory, independent of database Repository/user records.

## 4. Database and models

Mongoose schemas include timestamps: true inside the schema definition rather than as Schema options, so it appears to define a field rather than activate automatic createdAt/updatedAt timestamps.

User:
- username String required unique; email String required unique; password String optional.
- repositories ObjectId[] ref Repository; followedUsers ObjectId[] ref User; starRepos ObjectId[] ref Repository.
- Each array's default: [] is placed in the element schema; it is not a conventional array-level default.
- This model is unused by direct-driver user controller. Signup inserts arrays but bypasses Mongoose constraints.

Repository:
- name String required unique globally; description optional String; content String array; visibility optional Boolean; owner required ObjectId ref User; issues ObjectId[] ref Issue.
- Controller populates owner/issues on some reads. No cascade consistency logic.

Issue:
- title and description required String; status String enum open/closed default open; repository required ObjectId ref Repository.

### Proposed relational mapping

Use one consistent generated BIGINT or UUID key style, decision pending. Do not mirror bidirectional arrays as duplicated columns.

| Table | Proposed data and relationships |
|---|---|
| users | id PK, username/email/password_hash, created_at/updated_at; unique username and normalized email. Never return password_hash in DTOs. |
| repositories | id PK, owner_id FK users, name, description, visibility, timestamps; owner index. Decide global name uniqueness (current Mongoose declaration) versus recommended unique(owner_id,name). |
| issues | id PK, repository_id FK repositories, title, description, status, timestamps; indexes repository_id and repository_id/status. |
| user_follows | follower_id and followed_id FKs users, created_at, composite PK/unique, index followed_id. |
| repository_stars | user_id and repository_id FKs, created_at, composite PK/unique. |
| commits (when Git behavior is designed) | id, repository_id FK, author_id FK, parent_commit_id self-FK nullable, message, committed_at, manifest/storage reference; indexes repository/time and parent. |
| commit_files or manifest metadata (only if queryable in SQL) | commit_id FK, path, blob hash/storage key, size/mode; composite key commit_id/path. File bytes belong in storage, not MySQL. |

Define FK delete policy explicitly. Current deletion does not define it. Avoid repositories.issues and users.repositories arrays; query through foreign keys. Keep content array only as compatibility transition if required.

## 5. Authentication and authorization

Current behavior:
- Signup checks username in raw githubclone.users, hashes with bcryptjs generated salt cost 10, inserts user, signs JWT with claim id and JWT_SECRET_KEY, expiry one hour. It references result.insertId.
- Login searches email and compares password hash, then signs one-hour JWT using user._id.
- Token is returned in JSON. Frontend stores token under localStorage token, but sends it on no request.
- No jwt.verify call exists. Both middleware files are empty and unused.
- All routes are unauthenticated; IDs/owner are accepted from client parameters/body.
- Signup only checks duplicate username; direct Mongo driver bypasses Mongoose indexes/schema constraints.
- GET /allUsers returns all stored fields, including password hash.

Migration: AuthController DTO endpoints; BCryptPasswordEncoder (verify compatibility if migrating hashes); configured JWT signer and validator; stateless Spring Security filter chain that sets principal from verified stable ID; ownership from principal for protected operations. Define bearer-token versus secure-cookie strategy and update React requests accordingly. Current localStorage token behavior is not sufficient authentication. Decide public/private read rules.

## 6. Git-like behavior

Current system is a CLI filesystem/S3 prototype:
- State under .apnaGit in current working directory.
- init writes config and empty commits directory; add creates staging lazily.
- add strips directories to basename, so paths are lost and same-name files collide.
- commit creates UUID directory, copies all staged files and writes message/date JSON. Staging remains; no parent, branch, author, diff, hash, tree or head pointer.
- push uploads all local commit directory contents each time; stable UUID/file keys overwrite same objects on repetition.
- pull lists only one listObjectsV2 page (no continuation tokens), no repo namespace; config/staging are not fetched.
- revert overwrites target files in working directory, doesn't remove newer files or record a new revert commit.
- S3 uses fixed region and placeholder bucket constant; init writes env bucket, but push/pull use constant. Default AWS credential chain is used.

Proposed design: repository-scoped version-control service; MySQL stores commit metadata, parent/author and operation state; storage interface supports local development and later object storage; immutable blobs by content hash and manifests. Specify staging ownership/worktree, deletion/rename/diff semantics, authorization, pagination, idempotency and failure handling before implementation. Current commands do not implement Git protocol; decide whether target is a snapshot tool or Git-compatible client service.

## 7. Frontend analysis

- Entry: main.jsx renders ProjectRoutes under AuthProvider/BrowserRouter.
- App.jsx is unused Vite counter.
- Routes: / dashboard, /auth login, /signup, /profile. Reads userId from localStorage and uses its presence for redirects; no token validation. Navbar links /create and Profile tab links /repo, both undefined.
- Auth context contains currentUser ID and setter, initialized from localStorage only.
- Login POST http://localhost:3002/login with email/password; expects token/userId, stores both, sets context and hard navigates /. Generic alert on failure.
- Signup POST /signup with username/email/password; same expected response/storage/navigation.
- Dashboard GET /repo/user/:userID and /repo/all via fetch. Expects first response object.repositories, second raw array. Filters owned list by name in memory. Suggestions show all repositories without visibility filtering. Events are static.
- Profile GET /userProfile/:id via Axios, expects username; logout clears token/userId and navigates /auth. Follow button, counts (10 followers, 3 following) are static.
- HeatMap generates random counts for fixed January 2001 dates, no API.
- Navbar offers root, absent /create, /profile; uses remote GitHub logo.
- No issue UI, repo detail/create/edit page, or calls to repository mutation/issue/Git endpoints.
- State is local component state plus minimal context. Axios is used in auth/profile, fetch in dashboard; no shared API client/base URL.
- Dependencies include React/ReactDOM, Vite, Router, Axios, Primer CSS/React, heat map, test tooling, ESLint. index title and assets remain Vite defaults.

## 8. Frontend to backend API dependencies

Base URL in calls is http://localhost:3002. “Auth required currently” records installed middleware; none is attached.

| Frontend component | Method | Endpoint | Purpose | Auth required currently |
|---|---|---|---|---|
| Login | POST | /login | Authenticate credentials | No |
| Signup | POST | /signup | Create account | No |
| Profile | GET | /userProfile/:id | Load profile | No |
| Dashboard | GET | /repo/user/:userID | Load owned repositories | No |
| Dashboard | GET | /repo/all | Suggested repositories | No |

No frontend calls exist for repo mutations, issue endpoints, allUsers or CLI commands.

## 9. Current functional flows

- Signup form -> raw Mongo username check -> bcrypt hash and insert -> token/userId response (insertId mismatch risk) -> localStorage -> dashboard.
- Login -> email lookup/password comparison -> JWT response -> localStorage. Later calls omit token; route gating trusts userId presence only.
- Repository creation has no UI. External POST supplies owner/name/etc.; handler validates name and ObjectId shape then saves.
- Dashboard requests user repositories and all repositories. Empty owned-list response is 404, and dashboard does not handle status as empty state.
- Repository update appends body content and updates description. No UI call.
- Issue CRUD has no UI. Create/list route parameter mismatch; delete/list await defects as above.
- Git CLI runs in a shell working directory and operates independently from database repositories and web accounts.

## 10. Source-supported limitations

- Frontend port 3002 differs from backend default 3000; no proxy/config found.
- Mongoose and MongoDB driver access are mixed; user controller hard-codes database name.
- insertId/deleteCount result-field assumptions are suspect for modern MongoDB driver.
- Auth middleware is empty; JWT is never verified; every route is open and IDs/owner are client-controlled.
- allUsers response is unfiltered and may expose password hashes.
- Issue create/list route contracts mismatch handler parameters; delete omits await and list omits await.
- timestamps appears in schema fields, not schema options.
- Global unique repository name can block same name across users; visibility lacks default and all-list ignores visibility.
- Direct-driver user writes bypass Mongoose schema enforcement.
- CLI loses file paths, does not clear staging, has no commit graph/head, and revert cannot remove files.
- S3 bucket constant is placeholder, transfers lack repo namespace, pull does not paginate.
- CLI errors are swallowed and web repositories are not linked to CLI state.
- Frontend links to /create and /repo have no matching route.
- Dashboard does not handle empty-list 404; suggestions ignore visibility.
- Profile follow and counts, heat-map activity, dashboard events are static/random.
- JWT is stored but never sent; localStorage userId presence is treated as logged-in state.

## 11. Proposed new architecture

React SPA -- JSON REST API --> Spring Boot:
Controller -> DTO validation -> Service -> Spring Data JPA Repository -> Hibernate/JPA -> MySQL.
Spring Security provides stateless JWT validation and authenticated principal. A storage service abstraction isolates local development and later object storage. Controllers use DTOs, not persistence entities. React remains React, with a later custom UI redesign.

Suggested package tree:

    backend/src/main/java/com/versioncontrol/
      VersionControlApplication.java
      config/
      controller/
      dto/
      entity/
      exception/
      repository/
      security/
      service/

Use separate services for auth, users, repositories, issues, version control and storage. Do not copy the current GitHub-style UI as final design.

## 12. Proposed MySQL design

| Entity | Key fields, constraints and indexes |
|---|---|
| User | id PK; username/email/password_hash NOT NULL; timestamps; unique username and normalized email. |
| Repository | id PK; owner_id FK User NOT NULL; name NOT NULL; description nullable; visibility NOT NULL after default decision; timestamps; owner index; decide global unique name vs owner_id/name unique. |
| Issue | id PK; repository_id FK NOT NULL; title/description NOT NULL; status NOT NULL default OPEN; timestamps; repository/status indexes. |
| UserFollow | follower_id and followed_id FKs; composite PK; created_at; index followed_id. |
| RepositoryStar | user_id/repository_id FKs; composite PK; created_at; index repository_id. |
| Commit (future phase) | id PK; repository_id FK; author_id FK; parent_commit_id self-FK nullable; message, committed_at and manifest reference; repository/time and parent indexes. |
| CommitFile/manifest metadata (optional) | commit_id FK, path, blob hash/storage key, size/mode; composite key. Store file bytes externally. |

Use migrations and explicit delete behavior. Commit entities are justified by existing snapshot feature but should wait for semantic decisions. Exact SQL types and ID choice remain open.

## 13. Migration map

| Current | Proposed |
|---|---|
| Express route | Spring REST Controller |
| Mongoose model | Redesigned JPA Entity |
| Mongoose/raw Mongo queries | Spring Data JPA Repository |
| Controller business logic | Service layer |
| Empty JWT middleware | Spring Security chain + JWT filter |
| jsonwebtoken | Configured JWT service |
| bcryptjs | BCryptPasswordEncoder |
| MongoDB | MySQL tables and FK relationships |
| Local .apnaGit file operations | Version-control service |
| AWS SDK push/pull | Storage adapter behind interface |
| Axios/fetch | Shared React API client and configured auth strategy |

## 14. Implementation phases

### Phase 1 — Spring Boot foundation + MySQL
Goal: bootstrap application, profiles/config and MySQL connectivity.
Modules: app entry, build config, database config, health endpoint.
Dependencies: Java/Spring Boot version and local MySQL.
Done: app starts, connects, health responds, schema migration strategy selected.

### Phase 2 — JPA entities and database
Goal: User, Repository, Issue, follow/star mappings and migrations.
Modules: entity, Spring Data repository, migration scripts.
Dependencies: IDs, uniqueness/defaults, FK delete policy.
Done: schema reproducible and relationships match approved design.

### Phase 3 — Authentication/security/JWT
Goal: signup/login and stateless protected routes.
Modules: DTO/controller/service, password encoder, JWT service/filter/security config.
Dependencies: User schema and secret config.
Done: valid token works, invalid/expired tokens rejected, principal identifies owner.

### Phase 4 — Repository APIs
Goal: repository CRUD/list/visibility and access rules.
Modules: controller/service/repository/DTO.
Dependencies: auth and endpoint contracts.
Done: owner cannot be spoofed and visibility/read policies work.

### Phase 5 — Issue APIs
Goal: repository-scoped issue CRUD.
Modules: controller/service/repository/DTO.
Dependencies: repository access policy.
Done: issue relation, status rules and coherent route contracts.

### Phase 6 — Version-control engine
Goal: define init/staging/commit history/revert.
Modules: version-control services/controllers, commit metadata and manifests.
Dependencies: client model, staging scope and storage design.
Done: stable snapshots, parent/history and revert semantics.

### Phase 7 — Storage/push/pull
Goal: immutable file content through replaceable local/object storage adapters.
Modules: storage interface/adapters and transfer orchestration.
Dependencies: manifest and provider decision.
Done: scoped, repeatable and paginated transfer with observable failures.

### Phase 8 — React API integration
Goal: connect existing React flows to Spring API.
Modules: API client, auth context, login/signup/dashboard/profile.
Dependencies: stable DTOs, auth and CORS.
Done: correct base URL/auth strategy and handled errors/empty states.

### Phase 9 — Custom UI redesign
Goal: replace prototype UI with user-designed visual system.
Modules: design system and page components.
Dependencies: screen/product decisions.
Done: agreed screens work responsively and placeholders are deliberately addressed.

### Phase 10 — Testing, security and documentation
Goal: verify flows and document operation.
Modules: tests, API docs, security review, setup/deployment documentation.
Dependencies: completed features and target environment.
Done: agreed scenarios pass and docs/config guidance match implementation.

## 15. Important rebuild rules

- Old project is reference only.
- Architect cleanly; do not translate Node code line by line.
- Preserve behavior where appropriate, using Spring conventions.
- MySQL stores metadata and relationships.
- Do not unnecessarily store repository file contents in MySQL.
- Derive repository ownership from authenticated user, never arbitrary client owner ID.
- Keep React, redesign its UI later.
- Do not use existing GitHub-style UI as final design.
- Implement and verify one phase at a time.

## 16. Open questions / decisions

1. Repository names globally unique as currently declared, or unique per owner?
2. What is default visibility and public/private read policy?
3. BIGINT or UUID IDs; is data migration needed?
4. Bearer JWT or secure cookie/session for redesigned frontend?
5. What are intended repository-scoped issue endpoint contracts?
6. Should follow/star be implemented, given only model arrays/static UI currently exist?
7. Does init mean server-side operation, local CLI, or client integration?
8. Is target a snapshot tool or Git-compatible engine? Required branch/diff/delete/rename/merge/conflict behavior?
9. Is staging per user, repo, worktree or client; how choose author/parent?
10. Which object storage provider; local disk adapter for development?
11. What access rules apply to repositories/issues/push/pull?
12. Which custom UI screens/features are needed beyond current login/signup/dashboard/profile?
13. Should static profile counts, random heat map and dashboard events be replaced with real services or remain placeholders?


## Phase 2 implementation record

Core Database v1 is implemented in backend with User, Repository, UserFollow, and RepositoryStar entities, Spring Data repositories, and Flyway migration V1__create_core_tables.sql. MySQL contains the four application tables plus flyway_schema_history. Repository names are unique per owner; follow/star pairs are unique; self-follow is rejected; relationships use foreign keys and lazy many-to-one mappings. No future-phase entities or file contents were added.
