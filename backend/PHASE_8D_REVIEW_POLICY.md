# Phase 8D review policy

## Reviewer eligibility limitation

Repository creation and pull request creation are currently owner-only, and the pull request author is set to the repository owner. Phase 8D therefore rejects that account's `APPROVED` review as self-approval. The owner may submit `COMMENTED` or `CHANGES_REQUESTED` reviews. The pull request author may submit `COMMENTED` reviews, and other accounts are denied until explicit repository write/collaborator permissions exist. A meaningful multi-user approval workflow requires collaborator permissions and a way for an eligible reviewer who is not the pull request author to receive review access. Approval tests must not bypass this rule.

Private pull request reviews and comments are visible only to the repository owner, following the existing private-resource 404 convention. Public review and comment reads are anonymous. Review and comment writes require authentication and an eligible author.

## Review history and status

Each review is an append-only historical decision tied to the exact source branch HEAD captured while the source and target branch rows are locked. A later source commit makes that decision stale. Status uses the latest non-`COMMENTED` review per reviewer; `COMMENTED` entries add discussion without replacing a prior approval or change request. Self-reviews never count. No required approval threshold is configured in this phase, so `approvalThresholdConfigured` is `false` and `requiredReviewConditionsSatisfied` is `null`.

Standalone `POST /api/pull-requests/{id}/comments` creates a `COMMENTED` review containing one inline comment. This keeps all discussion attached to a reviewer's immutable reviewed-HEAD snapshot. Comment edits change only the comment body; the file path, side, line, and reviewed snapshot remain fixed.

Inline paths must be canonical tree paths present as changed files in the current PR diff. The selected OLD/NEW file must be supported UTF-8 text, and the 1-based line must exist in that exact snapshot. Binary files, unchanged or missing paths, invalid sides, and invalid lines are rejected. Review bodies are limited to 20,000 characters, comments to 10,000 characters, and a submission may contain at most 20 inline comments. Review/comment collections are paginated with a maximum page size of 50; status calculation is limited to 5,000 reviews per PR.
