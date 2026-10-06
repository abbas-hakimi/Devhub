package com.versioncontrol.service;

import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.versioncontrol.dto.label.CreateLabelRequest;
import com.versioncontrol.dto.label.LabelResponse;
import com.versioncontrol.dto.label.UpdateLabelRequest;
import com.versioncontrol.entity.Issue;
import com.versioncontrol.entity.IssueLabel;
import com.versioncontrol.entity.Label;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.IssueLabelRepository;
import com.versioncontrol.repository.IssueRepository;
import com.versioncontrol.repository.LabelRepository;

@Service
public class LabelService {
    private final LabelRepository labels;
    private final IssueRepository issues;
    private final IssueLabelRepository issueLabels;
    private final RepositoryAccessService access;

    public LabelService(LabelRepository labels, IssueRepository issues, IssueLabelRepository issueLabels, RepositoryAccessService access) {
        this.labels = labels; this.issues = issues; this.issueLabels = issueLabels; this.access = access;
    }

    @Transactional
    public LabelResponse create(Long repositoryId, CreateLabelRequest request, Long actorId) {
        var repository = access.find(repositoryId);
        access.requireReadable(repository, actorId);
        access.requireOwner(repository, actorId);
        String name = request.name().trim();
        ensureNameAvailable(repositoryId, name, null);
        try {
            return LabelResponse.from(labels.saveAndFlush(new Label(repository, name, clean(request.description()), request.color().toUpperCase(java.util.Locale.ROOT))));
        } catch (DataIntegrityViolationException exception) {
            throw new RepositoryConflictException("A label with this name already exists in the repository");
        }
    }

    @Transactional(readOnly = true)
    public List<LabelResponse> list(Long repositoryId, Long actorId) {
        var repository = access.find(repositoryId);
        access.requireReadable(repository, actorId);
        return labels.findByRepository_IdOrderByNameAsc(repositoryId).stream().map(LabelResponse::from).toList();
    }

    @Transactional
    public LabelResponse update(Long labelId, UpdateLabelRequest request, Long actorId) {
        Label label = findLabel(labelId);
        access.requireReadable(label.getRepository(), actorId);
        access.requireOwner(label.getRepository(), actorId);
        String name = request.name().trim();
        ensureNameAvailable(label.getRepository().getId(), name, labelId);
        label.setName(name);
        label.setDescription(clean(request.description()));
        label.setColor(request.color().toUpperCase(java.util.Locale.ROOT));
        try {
            return LabelResponse.from(labels.saveAndFlush(label));
        } catch (DataIntegrityViolationException exception) {
            throw new RepositoryConflictException("A label with this name already exists in the repository");
        }
    }

    @Transactional
    public void delete(Long labelId, Long actorId) {
        Label label = findLabel(labelId);
        access.requireReadable(label.getRepository(), actorId);
        access.requireOwner(label.getRepository(), actorId);
        labels.delete(label);
    }

    @Transactional
    public void attach(Long issueId, Long labelId, Long actorId) {
        Issue issue = issues.findById(issueId).orElseThrow(() -> new ResourceNotFoundException("Issue not found"));
        access.requireReadable(issue.getRepository(), actorId);
        access.requireOwner(issue.getRepository(), actorId);
        Label label = findLabel(labelId);
        access.requireReadable(label.getRepository(), actorId);
        access.requireOwner(label.getRepository(), actorId);
        if (!issue.getRepository().getId().equals(label.getRepository().getId())) {
            throw new InvalidRequestException("Issue and label must belong to the same repository");
        }
        if (issueLabels.existsByIssue_IdAndLabel_Id(issueId, labelId)) {
            throw new RepositoryConflictException("Label is already attached to this issue");
        }
        try {
            issueLabels.saveAndFlush(new IssueLabel(issue, label));
        } catch (DataIntegrityViolationException exception) {
            throw new RepositoryConflictException("Label is already attached to this issue");
        }
    }

    @Transactional
    public void detach(Long issueId, Long labelId, Long actorId) {
        Issue issue = issues.findById(issueId).orElseThrow(() -> new ResourceNotFoundException("Issue not found"));
        access.requireReadable(issue.getRepository(), actorId);
        access.requireOwner(issue.getRepository(), actorId);
        Label label = findLabel(labelId);
        access.requireReadable(label.getRepository(), actorId);
        access.requireOwner(label.getRepository(), actorId);
        if (!issue.getRepository().getId().equals(label.getRepository().getId())) {
            throw new InvalidRequestException("Issue and label must belong to the same repository");
        }
        if (!issueLabels.existsByIssue_IdAndLabel_Id(issueId, labelId)) {
            throw new ResourceNotFoundException("Issue label association not found");
        }
        issueLabels.deleteByIssue_IdAndLabel_Id(issueId, labelId);
    }

    private Label findLabel(Long id) { return labels.findById(id).orElseThrow(() -> new ResourceNotFoundException("Label not found")); }
    private void ensureNameAvailable(Long repositoryId, String name, Long exceptId) {
        boolean exists = exceptId == null
                ? labels.existsByRepository_IdAndNameIgnoreCase(repositoryId, name)
                : labels.existsByRepository_IdAndNameIgnoreCaseAndIdNot(repositoryId, name, exceptId);
        if (exists) throw new RepositoryConflictException("A label with this name already exists in the repository");
    }
    private String clean(String description) { return description == null || description.isBlank() ? null : description.trim(); }
}
