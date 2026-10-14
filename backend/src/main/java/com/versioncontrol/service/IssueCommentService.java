package com.versioncontrol.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.versioncontrol.dto.comment.CreateIssueCommentRequest;
import com.versioncontrol.dto.comment.IssueCommentResponse;
import com.versioncontrol.dto.comment.UpdateIssueCommentRequest;
import com.versioncontrol.entity.Issue;
import com.versioncontrol.entity.IssueComment;
import com.versioncontrol.entity.CollaboratorRole;
import com.versioncontrol.exception.ResourceForbiddenException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.IssueCommentRepository;
import com.versioncontrol.repository.IssueRepository;
import com.versioncontrol.repository.UserRepository;

@Service
public class IssueCommentService {
    private final IssueCommentRepository comments;
    private final IssueRepository issues;
    private final UserRepository users;
    private final RepositoryAccessService access;
    private final NotificationService notifications;

    public IssueCommentService(IssueCommentRepository comments, IssueRepository issues, UserRepository users, RepositoryAccessService access,
            NotificationService notifications) {
        this.comments = comments; this.issues = issues; this.users = users; this.access = access; this.notifications = notifications;
    }

    @Transactional
    public IssueCommentResponse create(Long issueId, CreateIssueCommentRequest request, Long actorId) {
        Issue issue = findIssue(issueId);
        access.requireReadable(issue.getRepository(), actorId);
        rejectReadOnlyCollaborator(issue.getRepository(), actorId);
        IssueComment saved = comments.saveAndFlush(new IssueComment(issue,
                users.findById(actorId).orElseThrow(() -> new ResourceNotFoundException("User not found")), request.body().trim()));
        notifications.notifyRecipients(List.of(issue.getAuthor().getId()), actorId, issue.getRepository(),
                com.versioncontrol.entity.NotificationEventType.ISSUE_COMMENTED, "New issue comment",
                "A comment was added to issue " + issue.getTitle(), "ISSUE", issue.getId(), "ISSUE_COMMENT:" + saved.getId());
        return IssueCommentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<IssueCommentResponse> list(Long issueId, Long actorId) {
        Issue issue = findIssue(issueId);
        access.requireReadable(issue.getRepository(), actorId);
        return comments.findByIssue_IdOrderByCreatedAtAsc(issueId).stream().map(IssueCommentResponse::from).toList();
    }

    @Transactional
    public IssueCommentResponse update(Long commentId, UpdateIssueCommentRequest request, Long actorId) {
        IssueComment comment = findComment(commentId);
        access.requireReadable(comment.getIssue().getRepository(), actorId);
        rejectReadOnlyCollaborator(comment.getIssue().getRepository(), actorId);
        requireAuthorOrOwner(comment, actorId);
        comment.setBody(request.body().trim());
        return IssueCommentResponse.from(comments.saveAndFlush(comment));
    }

    @Transactional
    public void delete(Long commentId, Long actorId) {
        IssueComment comment = findComment(commentId);
        access.requireReadable(comment.getIssue().getRepository(), actorId);
        rejectReadOnlyCollaborator(comment.getIssue().getRepository(), actorId);
        requireAuthorOrOwner(comment, actorId);
        comments.delete(comment);
    }

    private Issue findIssue(Long id) { return issues.findById(id).orElseThrow(() -> new ResourceNotFoundException("Issue not found")); }
    private IssueComment findComment(Long id) { return comments.findById(id).orElseThrow(() -> new ResourceNotFoundException("Comment not found")); }
    private void rejectReadOnlyCollaborator(com.versioncontrol.entity.Repository repository, Long actorId) {
        if (access.roleOf(repository, actorId) == CollaboratorRole.READ)
            throw new ResourceForbiddenException("READ collaborators cannot modify issue comments");
    }
    private void requireAuthorOrOwner(IssueComment comment, Long actorId) {
        if (!comment.getAuthor().getId().equals(actorId) && !access.hasMaintain(comment.getIssue().getRepository(), actorId)) {
            throw new ResourceForbiddenException("Only the comment author or a repository maintainer can modify it");
        }
    }
}
