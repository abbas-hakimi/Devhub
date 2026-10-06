package com.versioncontrol.service;

import java.time.LocalDateTime;
import java.util.List;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.versioncontrol.dto.milestone.CreateMilestoneRequest;
import com.versioncontrol.dto.milestone.MilestoneResponse;
import com.versioncontrol.dto.milestone.UpdateMilestoneRequest;
import com.versioncontrol.entity.Milestone;
import com.versioncontrol.entity.MilestoneState;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.MilestoneRepository;

@Service
public class MilestoneService {
    private final MilestoneRepository milestones;
    private final RepositoryAccessService access;
    private final Validator validator;

    public MilestoneService(MilestoneRepository milestones, RepositoryAccessService access, Validator validator) {
        this.milestones = milestones;
        this.access = access;
        this.validator = validator;
    }

    @Transactional
    public MilestoneResponse create(Long repositoryId, CreateMilestoneRequest request, Long actorId) {
        var repository = access.find(repositoryId);
        access.requireReadable(repository, actorId);
        access.requireOwner(repository, actorId);
        String title = request.title().trim();
        ensureTitleAvailable(repositoryId, title, null);
        try {
            return MilestoneResponse.from(milestones.saveAndFlush(new Milestone(repository, title, clean(request.description()), request.dueDate())));
        } catch (DataIntegrityViolationException exception) {
            throw duplicateTitle();
        }
    }

    @Transactional(readOnly = true)
    public List<MilestoneResponse> list(Long repositoryId, Long actorId) {
        var repository = access.find(repositoryId);
        access.requireReadable(repository, actorId);
        return milestones.findByRepositoryIdOrderByDueDateAscTitleAsc(repositoryId).stream().map(MilestoneResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public MilestoneResponse get(Long milestoneId, Long actorId) {
        Milestone milestone = findMilestone(milestoneId);
        access.requireReadable(milestone.getRepository(), actorId);
        return MilestoneResponse.from(milestone);
    }

    @Transactional
    public MilestoneResponse update(Long milestoneId, UpdateMilestoneRequest request, Long actorId) {
        Milestone milestone = findMilestone(milestoneId);
        access.requireReadable(milestone.getRepository(), actorId);
        access.requireOwner(milestone.getRepository(), actorId);
        var violations = validator.validate(request);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        if (request.isTitleSpecified()) {
            if (request.getTitle() == null || request.getTitle().isBlank()) {
                throw new InvalidRequestException("title must not be blank");
            }
            String title = request.getTitle().trim();
            ensureTitleAvailable(milestone.getRepository().getId(), title, milestoneId);
            milestone.setTitle(title);
        }
        if (request.isDescriptionSpecified()) milestone.setDescription(clean(request.getDescription()));
        if (request.isDueDateSpecified()) milestone.setDueDate(request.getDueDate());
        if (request.isStateSpecified()) {
            if (request.getState() == null) throw new InvalidRequestException("state must not be null");
            if (request.getState() != milestone.getState()) {
                milestone.setClosedAt(request.getState() == MilestoneState.CLOSED ? LocalDateTime.now() : null);
                milestone.setState(request.getState());
            }
        }
        try {
            return MilestoneResponse.from(milestones.saveAndFlush(milestone));
        } catch (DataIntegrityViolationException exception) {
            throw duplicateTitle();
        }
    }

    @Transactional
    public void delete(Long milestoneId, Long actorId) {
        Milestone milestone = findMilestone(milestoneId);
        access.requireReadable(milestone.getRepository(), actorId);
        access.requireOwner(milestone.getRepository(), actorId);
        milestones.clearIssueAssociations(milestoneId);
        milestones.delete(milestone);
    }

    private Milestone findMilestone(Long id) {
        return milestones.findById(id).orElseThrow(() -> new ResourceNotFoundException("Milestone not found"));
    }
    private void ensureTitleAvailable(Long repositoryId, String title, Long exceptId) {
        boolean exists = exceptId == null
                ? milestones.countByRepositoryAndNormalizedTitle(repositoryId, title) > 0
                : milestones.countByRepositoryAndNormalizedTitleExcludingId(repositoryId, title, exceptId) > 0;
        if (exists) throw duplicateTitle();
    }
    private RepositoryConflictException duplicateTitle() {
        return new RepositoryConflictException("A milestone with this title already exists in the repository");
    }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
