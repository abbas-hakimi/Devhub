package com.versioncontrol.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.versioncontrol.dto.issue.CreateIssueRequest;
import com.versioncontrol.dto.issue.IssueResponse;
import com.versioncontrol.dto.issue.UpdateIssueRequest;
import com.versioncontrol.dto.label.LabelResponse;
import com.versioncontrol.entity.Issue;
import com.versioncontrol.entity.IssueStatus;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.User;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.ResourceForbiddenException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.IssueLabelRepository;
import com.versioncontrol.repository.IssueRepository;
import com.versioncontrol.repository.UserRepository;

@Service
public class IssueService {
    private final IssueRepository issues;
    private final IssueLabelRepository issueLabels;
    private final UserRepository users;
    private final RepositoryAccessService access;

    public IssueService(IssueRepository issues, IssueLabelRepository issueLabels, UserRepository users, RepositoryAccessService access) {
        this.issues = issues; this.issueLabels = issueLabels; this.users = users; this.access = access;
    }

    @Transactional
    public IssueResponse create(Long repositoryId, CreateIssueRequest request, Long actorId) {
        Repository repository = access.find(repositoryId);
        access.requireReadable(repository, actorId);
        User author = findUser(actorId);
        User assignee = request.assigneeId() == null ? null : findAssignee(repository, author, request.assigneeId());
        Issue issue = new Issue(repository, author, assignee, request.title().trim(), clean(request.description()));
        return response(issues.saveAndFlush(issue));
    }

    @Transactional(readOnly = true)
    public List<IssueResponse> list(Long repositoryId, IssueStatus status, Long actorId) {
        Repository repository = access.find(repositoryId);
        access.requireReadable(repository, actorId);
        List<Issue> found = status == null
                ? issues.findByRepository_IdOrderByCreatedAtDesc(repositoryId)
                : issues.findByRepository_IdAndStatusOrderByCreatedAtDesc(repositoryId, status);
        if (found.isEmpty()) return List.of();
        List<Long> ids = found.stream().map(Issue::getId).toList();
        Map<Long, List<LabelResponse>> labelsByIssue = new HashMap<>();
        issueLabels.findByIssue_IdIn(ids).forEach(link -> labelsByIssue
                .computeIfAbsent(link.getIssue().getId(), ignored -> new ArrayList<>()).add(LabelResponse.from(link.getLabel())));
        return found.stream().map(issue -> IssueResponse.from(issue, labelsByIssue.getOrDefault(issue.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public IssueResponse get(Long issueId, Long actorId) {
        Issue issue = findIssue(issueId);
        access.requireReadable(issue.getRepository(), actorId);
        return response(issue);
    }

    @Transactional
    public IssueResponse update(Long issueId, UpdateIssueRequest request, Long actorId) {
        Issue issue = findIssue(issueId);
        Repository repository = issue.getRepository();
        access.requireReadable(repository, actorId);
        boolean owner = access.isOwner(repository, actorId);
        boolean author = issue.getAuthor().getId().equals(actorId);
        if (!owner && !author) throw new ResourceForbiddenException("Only the issue author or repository owner can update it");

        if (request.getStatus() != null && request.getStatus() != issue.getStatus() && !owner) {
            throw new ResourceForbiddenException("Only the repository owner can change issue status");
        }
        if (request.isAssigneeIdSpecified() && !owner
                && !java.util.Objects.equals(request.getAssigneeId(), issue.getAssignee() == null ? null : issue.getAssignee().getId())) {
            throw new ResourceForbiddenException("Only the repository owner can change issue assignee");
        }
        if (request.getTitle() != null) {
            if (request.getTitle().isBlank()) throw new InvalidRequestException("title must not be blank");
            issue.setTitle(request.getTitle().trim());
        }
        if (request.getDescription() != null) issue.setDescription(clean(request.getDescription()));
        if (owner && request.getStatus() != null && request.getStatus() != issue.getStatus()) {
            if (request.getStatus() == IssueStatus.CLOSED) issue.setClosedAt(LocalDateTime.now());
            else issue.setClosedAt(null);
            issue.setStatus(request.getStatus());
        }
        if (owner && request.isAssigneeIdSpecified()) {
            User assignee = request.getAssigneeId() == null ? null : findAssignee(repository, issue.getAuthor(), request.getAssigneeId());
            issue.setAssignee(assignee);
        }
        try {
            return response(issues.saveAndFlush(issue));
        } catch (DataIntegrityViolationException exception) {
            throw new RepositoryConflictException("Issue update conflicts with existing data");
        }
    }

    @Transactional
    public void delete(Long issueId, Long actorId) {
        Issue issue = findIssue(issueId);
        access.requireReadable(issue.getRepository(), actorId);
        access.requireOwner(issue.getRepository(), actorId);
        issues.delete(issue);
    }

    private Issue findIssue(Long id) { return issues.findById(id).orElseThrow(() -> new ResourceNotFoundException("Issue not found")); }
    private User findUser(Long id) { return users.findById(id).orElseThrow(() -> new ResourceNotFoundException("User not found")); }
    private User findAssignee(Repository repository, User author, Long assigneeId) {
        User assignee = findUser(assigneeId);
        if (!assignee.getId().equals(repository.getOwner().getId()) && !assignee.getId().equals(author.getId())) {
            throw new ResourceNotFoundException("Assignee is not allowed for this repository");
        }
        return assignee;
    }
    private IssueResponse response(Issue issue) {
        List<LabelResponse> labels = issueLabels.findByIssue_IdOrderByLabel_NameAsc(issue.getId()).stream()
                .map(link -> LabelResponse.from(link.getLabel())).toList();
        return IssueResponse.from(issue, labels);
    }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
