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
import com.versioncontrol.entity.Milestone;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.CollaboratorRole;
import com.versioncontrol.entity.User;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.ResourceForbiddenException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.IssueLabelRepository;
import com.versioncontrol.repository.MilestoneRepository;
import com.versioncontrol.repository.IssueRepository;
import com.versioncontrol.repository.UserRepository;

@Service
public class IssueService {
    private final IssueRepository issues;
    private final IssueLabelRepository issueLabels;
    private final UserRepository users;
    private final RepositoryAccessService access;
    private final MilestoneRepository milestones;

    public IssueService(IssueRepository issues, IssueLabelRepository issueLabels, UserRepository users, RepositoryAccessService access,
            MilestoneRepository milestones) {
        this.issues = issues; this.issueLabels = issueLabels; this.users = users; this.access = access; this.milestones = milestones;
    }

    @Transactional
    public IssueResponse create(Long repositoryId, CreateIssueRequest request, Long actorId) {
        Repository repository = access.find(repositoryId);
        access.requireReadable(repository, actorId);
        rejectReadOnlyCollaborator(repository, actorId);
        User author = findUser(actorId);
        User assignee = request.assigneeId() == null ? null : findAssignee(repository, author, request.assigneeId());
        Issue issue = new Issue(repository, author, assignee, request.title().trim(), clean(request.description()));
        return response(issues.saveAndFlush(issue));
    }

    @Transactional(readOnly = true)
    public List<IssueResponse> list(Long repositoryId, IssueStatus status, Long actorId) {
        Repository repository = access.find(repositoryId);
        access.requireReadable(repository, actorId);
        rejectReadOnlyCollaborator(repository, actorId);
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
        rejectReadOnlyCollaborator(issue.getRepository(), actorId);
        return response(issue);
    }

    @Transactional
    public IssueResponse update(Long issueId, UpdateIssueRequest request, Long actorId) {
        Issue issue = findIssue(issueId);
        Repository repository = issue.getRepository();
        access.requireReadable(repository, actorId);
        boolean writer = access.hasWrite(repository, actorId);
        boolean author = issue.getAuthor().getId().equals(actorId);
        if (!writer && !author) access.requireWrite(repository, actorId);

        if (request.getStatus() != null && request.getStatus() != issue.getStatus() && !writer) {
            throw new ResourceForbiddenException("Write access is required to change issue status");
        }
        if (request.isAssigneeIdSpecified() && !writer
                && !java.util.Objects.equals(request.getAssigneeId(), issue.getAssignee() == null ? null : issue.getAssignee().getId())) {
            throw new ResourceForbiddenException("Write access is required to change issue assignee");
        }
        if (request.isMilestoneIdSpecified() && !writer) {
            throw new ResourceForbiddenException("Write access is required to change issue milestone");
        }
        if (request.getTitle() != null) {
            if (request.getTitle().isBlank()) throw new InvalidRequestException("title must not be blank");
            issue.setTitle(request.getTitle().trim());
        }
        if (request.getDescription() != null) issue.setDescription(clean(request.getDescription()));
        if (writer && request.getStatus() != null && request.getStatus() != issue.getStatus()) {
            if (request.getStatus() == IssueStatus.CLOSED) issue.setClosedAt(LocalDateTime.now());
            else issue.setClosedAt(null);
            issue.setStatus(request.getStatus());
        }
        if (writer && request.isAssigneeIdSpecified()) {
            User assignee = request.getAssigneeId() == null ? null : findAssignee(repository, issue.getAuthor(), request.getAssigneeId());
            issue.setAssignee(assignee);
        }
        if (writer && request.isMilestoneIdSpecified()) {
            issue.setMilestone(request.getMilestoneId() == null ? null : findMilestoneForRepository(request.getMilestoneId(), repository));
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
        access.requireWrite(issue.getRepository(), actorId);
        issues.delete(issue);
    }

    private Issue findIssue(Long id) { return issues.findById(id).orElseThrow(() -> new ResourceNotFoundException("Issue not found")); }
    private void rejectReadOnlyCollaborator(Repository repository, Long actorId) {
        if (access.roleOf(repository, actorId) == CollaboratorRole.READ)
            throw new ResourceForbiddenException("READ collaborators cannot modify issues");
    }
    private User findUser(Long id) { return users.findById(id).orElseThrow(() -> new ResourceNotFoundException("User not found")); }
    private User findAssignee(Repository repository, User author, Long assigneeId) {
        User assignee = findUser(assigneeId);
        if (!assignee.getId().equals(repository.getOwner().getId()) && !assignee.getId().equals(author.getId())) {
            throw new ResourceNotFoundException("Assignee is not allowed for this repository");
        }
        return assignee;
    }
    private Milestone findMilestoneForRepository(Long milestoneId, Repository repository) {
        Milestone milestone = milestones.findById(milestoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Milestone not found"));
        if (!milestone.getRepository().getId().equals(repository.getId())) {
            throw new InvalidRequestException("Issue and milestone must belong to the same repository");
        }
        return milestone;
    }
    private IssueResponse response(Issue issue) {
        List<LabelResponse> labels = issueLabels.findByIssue_IdOrderByLabel_NameAsc(issue.getId()).stream()
                .map(link -> LabelResponse.from(link.getLabel())).toList();
        return IssueResponse.from(issue, labels);
    }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
