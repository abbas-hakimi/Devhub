# Phase 8G Backend Hardening Audit

Date: 2026-10-14

## Scope and outcome

Reviewed repository/branch/commit access, object browsing and path validation, pull-request lifecycle and reviews, collaborator role checks, merge locking and graph traversal, three-way tree merging, storage writes, response errors, and existing integration coverage. No Flyway migration was required. V1 through V8 remain unchanged. No cross-repository data exposure or merge-state defect was found in the inspected paths.

Two confirmed issues were fixed:

- The security entry point's `401` body returned only `status` and `error`, unlike validation, authorization, conflict, and not-found errors, which use `ApiError` with `timestamp`, `status`, `error`, and `fields`. The entry point now emits the shared envelope, covered by an authentication integration assertion.
- The issue list/get service called a helper intended to reject READ collaborators from modifying issues. That caused a `403` on read-only `GET` requests after a WRITE collaborator was downgraded to READ. The checks were removed from the GET methods while create/write checks remain. Collaborator integration coverage confirms the downgraded user can read issue list/details and remains blocked from writes.

## Merge and concurrency findings

- Merge transactions acquire locks in repository → pull request → branch ID order. Commit writes also lock the repository before the branch, serializing a merge against a source or target HEAD update.
- Merge requests verify both supplied HEAD hashes after acquiring locks. A request with stale heads returns `409`; duplicate submissions serialize and the second request sees the already-merged PR and returns `409`.
- The commit graph traverses both parent columns, validates parent repository membership, bounds graph size, detects cycles, and reports ambiguous best merge bases as requiring manual resolution.
- The tree merge handles unchanged files, identical results, disjoint one-range UTF-8 text edits, and reports conflicts for overlapping text, binary, add/add, modify/delete, and file/directory collisions. It intentionally does not try to merge more complex edits heuristically.
- Blob/tree/commit object writes use `ObjectStorage`. Object files are immutable, but the file store cannot participate in the JDBC transaction. A failed DB transaction may leave unreachable objects; it does not publish a branch head or PR state pointing at an uncommitted merge.
- Added regression coverage for a simultaneous source HEAD commit and PR merge, closed-PR merge rejection, add/add and modify/delete conflicts, and criss-cross history with ambiguous merge bases. Existing tests already cover duplicate merges, stale heads, two-parent traversal, disjoint text edits, binary and file/directory conflicts, stale approvals, current change requests, and review/commit races.

## Lifecycle, authorization, and privacy

- Merge requires an open PR, owner or `MAINTAIN` capability, unchanged expected heads, a usable merge base, and at least one current approval from a reviewer other than the PR author. A current change request blocks merge. Approval freshness is evaluated against the current source HEAD.
- Closing and reopening follow the established PR author/maintainer policy. The added lifecycle regression confirms a closed PR cannot merge and its target branch does not advance.
- Read access is checked at the repository for commit metadata, trees, blobs, PRs, reviews, comparisons, issues, labels, and milestones. Private repository misses use `404` to avoid existence disclosure. Mutations use current collaborator roles, so downgrade/removal affects the next request.
- Controller responses use DTOs; password hashes and persistence entities are not returned through the audited API routes.
- File path validation rejects invalid segments, absolute paths, backslashes, drive paths, and paths deeper than the configured limit. Servlet/container and Spring Security may reject encoded traversal forms before application path validation; callers should treat any early rejection as a failed request and must not rely on one exact status for malformed encoded URLs.
- Numeric route values that cannot bind to their declared type and invalid request parameters map to `400`; missing resources map to `404`; authenticated capability failures map to `403`; anonymous/invalid bearer requests map to `401`.

## API consistency and limits

- Authentication failures now use the shared `ApiError` JSON envelope. Validation errors include field messages; other known errors have an empty `fields` map.
- Pagination is compatible with existing consumers but currently has resource-specific response keys and limits. Commit history returns `commits`/`hasMore`; PR, review, comment, and comparison APIs have their own envelope shape and size caps. These are recorded in `BACKEND_API_REFERENCE.md`; changing them globally would break existing endpoint contracts.
- Commit content is limited to 1 MB per file and 10 MB total per request. History/merge graph walks are bounded. The merge algorithm only auto-combines one unambiguous contiguous edit per side for UTF-8 text under its size cap.

## Verification

- Full Java verification: `mvnw.cmd clean test` completed successfully: **99 tests, 0 failures, 0 errors, 0 skipped**.
- Python HTTP suite: `backend/phase8g_api_test.py` exercises private visibility, initialization, collaborator invitation, branch and commit creation, PR self-review rejection, approval, merge/idempotency, role downgrade, history access, and closed-PR merge rejection. It requires a running local backend, `postmanA` and `postmanB` accounts, and MySQL access for cleanup.
- Python run command from `backend`:

  ```powershell
  $env:PHASE8G_BASE_URL = 'http://localhost:8080'
  .\mvnw.cmd spring-boot:run
  # In another terminal, from backend:
  python .\phase8g_api_test.py
  ```

  The script prompts for both account passwords and the MySQL cleanup password. Keep the Spring Boot process running in the first terminal while the script runs in the second.
- Database schema: test context validates all eight existing Flyway migrations. No V9 migration was added.
- `git diff --check` completed with no whitespace errors. Git displayed the repository's normal LF-to-CRLF conversion notices for modified Java files.

## Known limitations and remaining risks

- Local file object storage is a single-host implementation; production deployment needs durable shared storage, backups, and an operational integrity scan.
- An interrupted transaction can leave unreferenced immutable objects. No garbage collector currently removes them.
- Complex text changes, binary conflicts, overlapping edits, and ambiguous criss-cross merge bases require manual resolution; the backend does not provide a conflict-resolution editor.
- The 50,000-commit graph cap and bounded history pages are deliberate safeguards and can limit unusually large repositories.
- Pagination shapes and limits differ across endpoint families. They are documented for frontend integration but are not yet unified.
- This audit covers the current backend implementation and automated tests; it does not replace deployment-level rate limiting, TLS/secret management, monitoring, or production database backup testing.
