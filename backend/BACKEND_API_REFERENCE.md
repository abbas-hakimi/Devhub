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

### Tags and releases

| Method and path | Access | Purpose |
|---|---|---|
| `POST /api/repositories/{id}/tags` | Write | Create an immutable tag at a commit in the same repository |
| `GET /api/repositories/{id}/tags` | Repository read | Paginated tag list (`page`, `size`) |
| `GET /api/repositories/{id}/tags/{name}` | Repository read | Read tag details |
| `DELETE /api/repositories/{id}/tags/{name}` | Maintainer/owner | Delete an unreferenced tag |
| `POST /api/repositories/{id}/releases` | Maintainer/owner | Create a release for a repository tag |
| `GET /api/repositories/{id}/releases` | Repository read | Paginated releases (`page`, `size`); drafts are visible only to maintainers/owners |
| `GET /api/repositories/{id}/releases/{releaseId}` | Repository read | Read published release; drafts are visible only to maintainers/owners |
| `PATCH /api/repositories/{id}/releases/{releaseId}` | Maintainer/owner | Partially update release metadata or publication state |
| `DELETE /api/repositories/{id}/releases/{releaseId}` | Maintainer/owner | Delete a release without deleting its tag |

Create a tag with `{"name":"v1.2.3","commitHash":"<commit hash>"}`. Tag names are single path segments, limited to 255 characters, and unique within a repository. They cannot contain separators, traversal segments, or Git's reserved `.lock` suffix. Tags are immutable after creation. A tag must point to a commit in the same repository. Deleting a tag still referenced by a release returns `409`.

Create a release with `{"tagName":"v1.2.3","title":"Version 1.2.3","description":"Release notes","draft":true,"prerelease":false}`. `draft` defaults to `true`; `prerelease` defaults to `false`. A repository can have at most one release per tag. `PATCH` accepts any non-empty subset of `title`, `description`, `draft`, and `prerelease`; omitted fields remain unchanged. A release's `publishedAt` is set when it becomes published, retained for edits while published, and cleared when it returns to draft. Releases and tags use standard page envelopes with `items`, `page`, `size`, and `totalElements`.

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

### Repository discussions

| Method and path | Access | Purpose |
|---|---|---|
| `GET /api/repositories/{id}/discussions?page=0&size=20` | Public repository read; authorized member for private | Paginated discussion list, newest discussion number first |
| `POST /api/repositories/{id}/discussions` | `WRITE`, `MAINTAIN`, or owner; announcements require `MAINTAIN`/owner | Create a discussion |
| `GET /api/discussions/{discussionId}` | Public repository read; authorized member for private | Read a discussion and reaction counts |
| `PATCH /api/discussions/{discussionId}` | Discussion author, `MAINTAIN`, or owner | Partially update title/body |
| `POST /api/discussions/{discussionId}/close` | Discussion author, `MAINTAIN`, or owner | Close the discussion |
| `POST /api/discussions/{discussionId}/reopen` | Discussion author, `MAINTAIN`, or owner | Reopen while retaining its history |
| `DELETE /api/discussions/{discussionId}` | `MAINTAIN` or owner | Delete discussion, replies, and reactions |
| `GET /api/discussions/{discussionId}/replies?page=0&size=20` | Public repository read; authorized member for private | Paginated replies, oldest first |
| `POST /api/discussions/{discussionId}/replies` | `WRITE`, `MAINTAIN`, or owner | Add a reply or one-level child reply |
| `PATCH /api/discussion-replies/{replyId}` | Reply author, `MAINTAIN`, or owner | Edit reply body |
| `DELETE /api/discussion-replies/{replyId}` | Reply author, `MAINTAIN`, or owner | Delete reply if it has no children |
| `POST /api/discussions/{discussionId}/reactions` | Authenticated repository reader | Add reaction (`{"type":"HEART"}`) |
| `DELETE /api/discussions/{discussionId}/reactions?type=HEART` | Authenticated repository reader | Remove caller's reaction of that type |
| `POST /api/discussion-replies/{replyId}/reactions` | Authenticated repository reader | Add reply reaction (`{"type":"ROCKET"}`) |
| `DELETE /api/discussion-replies/{replyId}/reactions?type=ROCKET` | Authenticated repository reader | Remove caller's reply reaction |
| `PUT /api/discussions/{discussionId}/accepted-answer` | Discussion author, `MAINTAIN`, or owner; Q&A only | Set `{"replyId":123}` as accepted answer |
| `DELETE /api/discussions/{discussionId}/accepted-answer` | Discussion author, `MAINTAIN`, or owner | Clear accepted answer |

Discussion categories are `GENERAL`, `Q_AND_A`, `IDEAS`, and `ANNOUNCEMENTS`; state is `OPEN` or `CLOSED`. Create payloads contain `category`, `title` (1–255 characters), and Markdown `body` (1–64,000 characters). PATCH accepts a non-empty subset of `title` and `body`. Reply bodies are limited to 16,000 characters and accept an optional `parentReplyId`; nesting is limited to one child level. Closed discussions reject new replies with `409`.

Accepted answers can only be set on Q&A discussions and must reference a reply in that same discussion. Deleting the accepted reply clears the reference. Replies with children cannot be deleted; delete child replies first. Deleting a discussion removes its replies and reactions after clearing its accepted answer. Categories, state, reply-parent, accepted-answer, and reaction type constraints are also enforced by V10 foreign keys/checks and unique indexes.

Reaction types are `THUMBS_UP`, `HEART`, `CELEBRATE`, and `ROCKET`. Each authenticated user can have at most one reaction of each type per discussion or reply. Repeated addition is idempotent, and removal affects only the caller's reaction. Discussion and reply responses include zero-filled reaction counts. Private resource reads return `404` to users without access; mutation attempts by visible non-members return `403`, and missing resources return `404`.

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

## Notifications and repository activity (Phase 9C)

All notification routes require a valid JWT. A caller sees only their own notifications; a notification belonging to another account returns `404`. Repository-scoped notifications are filtered against current repository visibility and accepted collaborator access, so permission revocation removes private-repository notifications from list and unread-count results. Pending collaborator invitations are the exception: the invitee can see the invitation notice because the invitation itself grants that limited information. `PATCH` read/unread operations are idempotent. `unread=true` filters to unread items and `unread=false` filters to read items. Pages use `page` (zero-based) and `size` (1–100), newest first, and return `items`, `totalElements`, `totalPages`, and `hasNext`.

| Method and route | Permission | Behavior |
| --- | --- | --- |
| `GET /api/notifications?unread=true&page=0&size=20` | Authenticated | List caller's notifications, newest first; omit `unread` to include both states |
| `GET /api/notifications/unread-count` | Authenticated | Count unread notifications visible under current repository access |
| `PATCH /api/notifications/{notificationId}/read` | Recipient | Mark read and return the notification |
| `PATCH /api/notifications/{notificationId}/unread` | Recipient | Mark unread and return the notification |
| `POST /api/notifications/mark-all-read` | Authenticated | Mark all currently visible unread notifications read; returns `markedRead` |
| `GET /api/repositories/{repositoryId}/activity?page=0&size=20` | Public repository: anonymous; private: repository reader | List safe activity summaries newest first |

Notification event types are `COLLABORATOR_INVITED`, `COLLABORATOR_ACCEPTED`, `COLLABORATOR_DECLINED`, `ISSUE_COMMENTED`, `PULL_REQUEST_REVIEWED`, `PULL_REQUEST_REVIEW_COMMENTED`, `DISCUSSION_REPLIED`, `DISCUSSION_REPLY_REPLIED`, `DISCUSSION_ANSWER_ACCEPTED`, and `RELEASE_PUBLISHED`. Actors never receive a notification for their own action. Repeated notification keys are suppressed. Release notifications go to the 500 most recently starred users (by star ID) at most; there is no new subscription model or unbounded release fan-out. Pending invite notices are recipient scoped and do not carry a repository foreign key so the invitee can read them before accepting.

Activity event types are `REPOSITORY_CREATED`, `ISSUE_OPENED`, `ISSUE_CLOSED`, `PULL_REQUEST_OPENED`, `PULL_REQUEST_MERGED`, `PULL_REQUEST_CLOSED`, `COMMIT_CREATED`, `BRANCH_CREATED`, `RELEASE_PUBLISHED`, `DISCUSSION_CREATED`, and `DISCUSSION_ANSWER_ACCEPTED`. Activity is stored in the same transaction as its originating operation. Summaries contain resource names or short commit hash prefixes only; request bodies, release notes, and file contents are not included. Follow, star/reaction, issue-comment, pull-request-review, and discussion-reply actions do not create activity feed entries.

Both page endpoints cap `size` at 100 and `page` at 2000. Resource IDs in notification targets are references only; clients should fetch the resource through its normal API, which applies its own current authorization checks. The Python HTTP suite is `phase9c_api_test.py`; see its module documentation and the Phase 9C audit for the exact local commands and MySQL cleanup scope.
