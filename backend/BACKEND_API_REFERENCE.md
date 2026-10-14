# DevHub Backend API Reference

Base URL: `http://localhost:8080`. API routes use the `/api` prefix and JSON request/response bodies unless a blob endpoint is streaming file bytes.

## Authentication and access

Register at `POST /api/auth/register`, then log in at `POST /api/auth/login`. Protected requests use `Authorization: Bearer <accessToken>`. `GET /api/auth/me` returns the authenticated user's safe profile. The API is stateless; send the token on every protected request.

Public repository reads are available without a token. Private repository reads are available to the owner and accepted collaborators. An inaccessible private repository is reported as `404` so its existence is not disclosed. Writes require an accepted collaborator role or ownership as described below. Owner-only actions include repository settings/deletion, collaborator administration, and milestone management. Merge operations require repository owner or `MAINTAIN`; a current approval from a reviewer other than the PR author is also required.

## Error response

Application and authentication errors use:

```json
{
  "timestamp": "2026-10-14T10:00:00Z",
  "status": 400,
  "error": "Validation failed",
  "fields": { "title": "must not be blank" }
}
```

`fields` is an empty object when the error does not refer to input fields. Common statuses are `400` for invalid input, `401` for missing/invalid authentication, `403` for an authenticated user lacking a required capability, `404` for a missing or hidden private resource, and `409` for stale heads, invalid lifecycle transitions, conflicts, or uniqueness collisions. Successful creation normally returns `201`; deletion may return `204`.

## Pagination

Paginated responses include their page metadata and an array. Pages are zero-based. Pull-request lists accept `page` (default `0`) and `size` (default `20`, maximum `100`) and return `totalElements`, `totalPages`, and `pullRequests`. Review and review-comment lists use `page` (default `0`) and `size` (default `20`, maximum `50`) and return total counts and their respective arrays. Branch comparisons accept `page` (default `0`) and `size` (default `10`, maximum `10`) and return comparison metadata plus a page of commits/files. Commit history accepts `branch` (default `main`), `page` (default `0`), and `size` (default `20`, maximum `100`); it returns `hasMore` and `commits`. Invalid limits return `400`.

## Endpoints

### Authentication

| Method and path | Access | Purpose |
|---|---|---|
| `POST /api/auth/register` | Anonymous | Create account and return token/profile |
| `POST /api/auth/login` | Anonymous | Authenticate by username or email |
| `GET /api/auth/me` | Authenticated | Return current user's safe profile |
| `GET /api/health` | Anonymous | Backend readiness response |

### Repositories and collaborators

| Method and path | Access | Purpose |
|---|---|---|
| `POST /api/repositories` | Authenticated | Create repository; owner is the caller |
| `GET /api/repositories` | Anonymous | List public repositories; optional `owner`, `name` filters |
| `GET /api/repositories/mine` | Authenticated | List caller's repositories |
| `GET /api/repositories/{id}` | Public/authorized private read | Get repository metadata |
| `PUT /api/repositories/{id}` | Owner | Update repository metadata/visibility |
| `DELETE /api/repositories/{id}` | Owner | Delete only when data-integrity rules allow it |
| `POST /api/repositories/{id}/collaborators/invitations` | Owner/maintainer per role policy | Invite collaborator with a role |
| `GET /api/repositories/{id}/collaborators` | Owner/maintainer | List collaborators |
| `GET /api/me/collaboration-invitations` | Authenticated | List caller's invitations |
| `POST /api/collaboration-invitations/{id}/accept` | Invitee | Accept invitation |
| `POST /api/collaboration-invitations/{id}/decline` | Invitee | Decline invitation |
| `PUT /api/repositories/{id}/collaborators/{userId}/role` | Owner | Change collaborator role; applies immediately |
| `DELETE /api/repositories/{id}/collaborators/{userId}` | Owner | Revoke collaborator access |

Collaborator roles are `READ`, `WRITE`, and `MAINTAIN`. Ownership remains with the repository owner and is not accepted from request data. `WRITE` can contribute commits and open pull requests. `MAINTAIN` includes repository maintenance capabilities but does not replace owner-only collaborator administration.

### Git data and browsing

| Method and path | Access | Purpose |
|---|---|---|
| `POST /api/repositories/{id}/git/init` | Owner | Idempotently initialize `main` and the initial commit |
| `POST /api/repositories/{id}/branches` | Write | Create branch from an existing repository commit |
| `GET /api/repositories/{id}/branches` | Repository read | List branches and heads |
| `GET /api/branches/{branchId}` | Repository read | Get branch details |
| `POST /api/repositories/{id}/commits` | Write | Create a commit using expected HEAD and file changes |
| `GET /api/repositories/{id}/commits` | Repository read | Browse branch history |
| `GET /api/commits/{commitHash}` | Repository read | Read commit metadata |
| `GET /api/commits/{commitHash}/tree` | Repository read | List root tree entries |
| `GET /api/commits/{commitHash}/tree/{path}` | Repository read | List a nested directory |
| `GET /api/commits/{commitHash}/blob/{path}` | Repository read | Stream blob bytes; supports ETag/304 |
| `GET /api/repositories/{id}/compare?base={branch}&head={branch}` | Repository read | Compare branches, merge base, commits and files |

Commit changes accept a path and exactly one of UTF-8 `content` or Base64 `contentBase64`; deletions use `deletedPaths`. Each file is limited to 1 MB and total replacement content to 10 MB. Paths are relative slash-separated paths, validated against traversal, invalid segments, and a maximum depth. Expected HEAD hashes prevent lost updates; stale values return `409`.

### Issues, labels, and milestones

| Method and path | Access | Purpose |
|---|---|---|
| `POST /api/repositories/{id}/issues` | Write | Create issue |
| `GET /api/repositories/{id}/issues` | Repository read | List issues, optionally filter by status |
| `GET /api/issues/{issueId}` | Repository read | Get issue |
| `PUT /api/issues/{issueId}` | Write | Update issue fields/status |
| `DELETE /api/issues/{issueId}` | Maintainer/owner | Delete issue |
| `POST /api/issues/{issueId}/comments` | Write | Add issue comment |
| `GET /api/issues/{issueId}/comments` | Repository read | List issue comments |
| `PUT /api/issues/comments/{commentId}` | Comment author/maintainer | Edit comment |
| `DELETE /api/issues/comments/{commentId}` | Comment author/maintainer | Delete comment |
| `POST /api/repositories/{id}/labels` | Owner/maintainer | Create repository label |
| `GET /api/repositories/{id}/labels` | Repository read | List labels |
| `PUT /api/labels/{labelId}` | Owner/maintainer | Update label |
| `DELETE /api/labels/{labelId}` | Owner/maintainer | Delete label |
| `POST /api/issues/{issueId}/labels/{labelId}` | Write | Assign label from same repository |
| `DELETE /api/issues/{issueId}/labels/{labelId}` | Write | Remove issue label |
| `POST /api/repositories/{id}/milestones` | Owner | Create milestone |
| `GET /api/repositories/{id}/milestones` | Repository read | List milestones |
| `GET /api/milestones/{milestoneId}` | Repository read | Get milestone |
| `PUT /api/milestones/{milestoneId}` | Owner | Partially update milestone |
| `DELETE /api/milestones/{milestoneId}` | Owner | Delete milestone; linked issues are unassigned |

An issue's `milestoneId` is optional and presence-aware during updates: omitted means unchanged; `null` clears it. Assignment to a milestone in another repository is rejected. Milestone title uniqueness is case-insensitive after trimming within one repository.

### Pull requests and reviews

| Method and path | Access | Purpose |
|---|---|---|
| `POST /api/repositories/{id}/pull-requests` | Write | Open PR between repository branches |
| `GET /api/repositories/{id}/pull-requests` | Repository read | List/filter PRs by state or author |
| `GET /api/pull-requests/{id}` | Repository read | Get PR |
| `PUT /api/pull-requests/{id}` | PR author/maintainer | Update PR details |
| `POST /api/pull-requests/{id}/close` | PR author/maintainer | Close open PR |
| `POST /api/pull-requests/{id}/reopen` | PR author/maintainer | Reopen closed PR |
| `GET /api/pull-requests/{id}/diff` | Repository read | Paginated PR file diff |
| `POST /api/pull-requests/{id}/reviews` | Write | Submit approval, change request, or comment review; PR author cannot approve own PR |
| `GET /api/pull-requests/{id}/reviews` | Repository read | Paginated reviews |
| `GET /api/pull-requests/{id}/review-status` | Repository read | Current approval/change-request summary |
| `GET /api/pull-requests/{id}/comments` | Repository read | Paginated inline/standalone review comments |
| `POST /api/pull-requests/{id}/comments` | Write | Add standalone review comment |
| `PUT /api/pull-requests/comments/{commentId}` | Comment author/maintainer | Edit review comment |
| `DELETE /api/pull-requests/comments/{commentId}` | Comment author/maintainer | Delete review comment |
| `GET /api/pull-requests/{id}/mergeability` | Repository read | Evaluate heads, merge base, reviews, and conflicts |
| `POST /api/pull-requests/{id}/merge` | Owner/maintainer | Merge using `FAST_FORWARD` or `MERGE_COMMIT` |

Merge requests carry `expectedSourceHead` and `expectedTargetHead`; stale values return `409`. `FAST_FORWARD` requires a fast-forwardable graph. `MERGE_COMMIT` creates a two-parent commit. Current approvals are tied to the current source HEAD; the PR author cannot approve their own PR and unresolved current change requests block merging. Closed or already merged PRs cannot merge. A merge with file conflicts returns `409` and conflict paths without moving the target head.

## Compatibility notes

The API currently has different page envelope names and limits for commit history, comparisons, PRs, and review resources. Clients should use the envelope documented for each route rather than assuming a single generic pagination object. Blob requests return raw content with a content type inferred from the path and an ETag based on the blob hash.
