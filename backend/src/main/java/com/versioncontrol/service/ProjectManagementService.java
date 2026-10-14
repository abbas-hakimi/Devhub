package com.versioncontrol.service;

import com.versioncontrol.dto.project.*;
import com.versioncontrol.entity.*;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.exception.RepositoryConflictException;
import com.versioncontrol.exception.ResourceForbiddenException;
import com.versioncontrol.exception.ResourceNotFoundException;
import com.versioncontrol.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectManagementService {
    private static final List<String> DEFAULT_COLUMNS = List.of("Todo", "In Progress", "In Review", "Done");
    private static final int MAX_COLUMNS = 100;
    private static final int MAX_CARDS_PER_COLUMN = 10_000;
    private static final int MAX_BOARD_CARDS = 500;
    private static final int TEMP_POSITION = 1_000_000_000;

    private final ProjectRepository projects;
    private final ProjectColumnRepository columns;
    private final ProjectCardRepository cards;
    private final RepositoryAccessService repositoryAccess;
    private final RepositoryRepository repositories;
    private final UserRepository users;
    private final IssueRepository issues;
    private final PullRequestRepository pullRequests;
    private final RepositoryActivityService activity;
    private final EntityManager entityManager;

    public ProjectManagementService(ProjectRepository projects, ProjectColumnRepository columns, ProjectCardRepository cards,
            RepositoryAccessService repositoryAccess, RepositoryRepository repositories, UserRepository users,
            IssueRepository issues, PullRequestRepository pullRequests, RepositoryActivityService activity,
            EntityManager entityManager) {
        this.projects = projects; this.columns = columns; this.cards = cards; this.repositoryAccess = repositoryAccess;
        this.repositories = repositories; this.users = users; this.issues = issues; this.pullRequests = pullRequests;
        this.activity = activity; this.entityManager = entityManager;
    }

    @Transactional
    public ProjectResponse create(CreateProjectRequest request, Long actorId) {
        if (request.scope() == null) throw new InvalidRequestException("Project scope is required");
        User actor = users.findById(actorId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Project project;
        if (request.scope() == ProjectScope.PERSONAL) {
            if (request.repositoryId() != null) throw new InvalidRequestException("Personal projects cannot specify a repository");
            if (projects.existsByOwner_IdAndScopeAndNameIgnoreCase(actorId, ProjectScope.PERSONAL, normalizeName(request.name())))
                throw new RepositoryConflictException("A personal project with this name already exists");
            project = new Project(normalizeName(request.name()), request.description(), ProjectScope.PERSONAL, actor, null, RepositoryVisibility.PRIVATE);
        } else {
            if (request.repositoryId() == null) throw new InvalidRequestException("Repository projects require a repositoryId");
            Repository repository = repositoryAccess.find(request.repositoryId());
            repositoryAccess.requireMaintain(repository, actorId);
            if (projects.existsByRepository_IdAndNameIgnoreCase(repository.getId(), normalizeName(request.name())))
                throw new RepositoryConflictException("A project with this name already exists in the repository");
            project = new Project(normalizeName(request.name()), request.description(), ProjectScope.REPOSITORY, repository.getOwner(), repository, null);
        }
        project = projects.saveAndFlush(project);
        for (int i = 0; i < DEFAULT_COLUMNS.size(); i++) columns.save(new ProjectColumn(project, DEFAULT_COLUMNS.get(i), i));
        if (project.getRepository() != null) activity.record(project.getRepository(), actorId,
                RepositoryActivityEventType.PROJECT_CREATED, "PROJECT", project.getId(), "Created project " + project.getName(), "project-created:" + project.getId());
        return ProjectResponse.from(project);
    }

    @Transactional(readOnly = true)
    public ProjectPageResponse list(int page, int size, Long userId) {
        validatePage(page, size);
        List<Project> candidates = new ArrayList<>();
        if (userId != null) candidates.addAll(projects.findByOwner_IdAndScopeOrderByUpdatedAtDescIdDesc(userId, ProjectScope.PERSONAL));
        Set<Long> readableRepositoryIds = repositories.findByVisibilityOrderByCreatedAtDesc(RepositoryVisibility.PUBLIC).stream()
                .map(Repository::getId).collect(Collectors.toSet());
        if (userId != null) {
            readableRepositoryIds.addAll(repositoryAccess.acceptedRepositoryIds(userId));
            readableRepositoryIds.addAll(repositories.findByOwner_IdOrderByCreatedAtDesc(userId).stream().map(Repository::getId).toList());
        }
        if (!readableRepositoryIds.isEmpty()) candidates.addAll(projects.findByRepository_IdInOrderByUpdatedAtDescIdDesc(new ArrayList<>(readableRepositoryIds)));
        List<Project> visible = candidates.stream().collect(Collectors.toMap(Project::getId, Function.identity(), (a, b) -> a))
                .values().stream().sorted(Comparator.comparing(Project::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Project::getId, Comparator.reverseOrder())).toList();
        Page<Project> result = new PageImpl<>(visible, PageRequest.of(page, size), visible.size());
        return new ProjectPageResponse(result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                result.hasNext(), result.getContent().stream().map(ProjectResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(Long id, Long userId) { return ProjectResponse.from(readableProject(id, userId)); }

    @Transactional
    public ProjectResponse update(Long id, UpdateProjectRequest request, Long userId) {
        Project project = lockProject(id, userId);
        requireManage(project, userId);
        requireActive(project);
        if (request.name() == null && request.description() == null) throw new InvalidRequestException("At least one project field is required");
        if (request.name() != null) {
            String name = normalizeName(request.name());
            boolean duplicate = project.getScope() == ProjectScope.PERSONAL
                    ? projects.existsByOwner_IdAndScopeAndNameIgnoreCase(project.getOwner().getId(), project.getScope(), name)
                    : projects.existsByRepository_IdAndNameIgnoreCase(project.getRepository().getId(), name);
            if (duplicate && !project.getName().equalsIgnoreCase(name)) throw new RepositoryConflictException("A project with this name already exists");
            project.setName(name);
        }
        if (request.description() != null) project.setDescription(request.description());
        return ProjectResponse.from(project);
    }

    @Transactional
    public void delete(Long id, Long userId) {
        Project project = lockProject(id, userId);
        requireManage(project, userId);
        // Cards restrict deletion of their columns. Remove only the board-owned rows;
        // issue and pull-request rows are separate resources and must be preserved.
        cards.deleteAll(cards.findBoardByProject(id));
        cards.flush();
        columns.deleteAll(columns.findByProject_IdOrderByPositionAscIdAsc(id));
        columns.flush();
        projects.delete(project);
        projects.flush();
    }

    @Transactional
    public ProjectResponse archive(Long id, Long userId, boolean restore) {
        Project project = lockProject(id, userId);
        requireManage(project, userId);
        ProjectStatus next = restore ? ProjectStatus.ACTIVE : ProjectStatus.ARCHIVED;
        if (project.getStatus() == next) return ProjectResponse.from(project);
        project.setStatus(next);
        if (project.getRepository() != null) activity.record(project.getRepository(), userId,
                restore ? RepositoryActivityEventType.PROJECT_RESTORED : RepositoryActivityEventType.PROJECT_ARCHIVED,
                "PROJECT", project.getId(), (restore ? "Restored project " : "Archived project ") + project.getName(), null);
        return ProjectResponse.from(project);
    }

    @Transactional(readOnly = true)
    public List<ProjectColumnResponse> listColumns(Long projectId, Long userId) {
        readableProject(projectId, userId);
        return columns.findByProject_IdOrderByPositionAscIdAsc(projectId).stream().map(ProjectColumnResponse::from).toList();
    }

    @Transactional
    public ProjectColumnResponse addColumn(Long projectId, CreateProjectColumnRequest request, Long userId) {
        Project project = lockProject(projectId, userId); requireManage(project, userId); requireActive(project);
        if (columns.countByProject_Id(projectId) >= MAX_COLUMNS) throw new InvalidRequestException("A project can contain at most " + MAX_COLUMNS + " columns");
        String name = normalizeColumnName(request.name());
        if (columns.existsByProject_IdAndNameIgnoreCase(projectId, name)) throw new RepositoryConflictException("A column with this name already exists");
        List<ProjectColumn> current = columns.findByProject_IdOrderByPositionAscIdAsc(projectId);
        return ProjectColumnResponse.from(columns.saveAndFlush(new ProjectColumn(project, name, current.size())));
    }

    @Transactional
    public ProjectColumnResponse renameColumn(Long columnId, UpdateProjectColumnRequest request, Long userId) {
        ProjectColumn existing = columns.findById(columnId).orElseThrow(() -> new ResourceNotFoundException("Project column not found"));
        Project project = lockProject(existing.getProject().getId(), userId); requireManage(project, userId); requireActive(project);
        ProjectColumn column = columns.lockById(columnId).orElseThrow(() -> new ResourceNotFoundException("Project column not found"));
        entityManager.refresh(column, LockModeType.PESSIMISTIC_WRITE);
        if (request.name() == null) throw new InvalidRequestException("Column name is required");
        String name = normalizeColumnName(request.name());
        if (!column.getName().equalsIgnoreCase(name) && columns.existsByProject_IdAndNameIgnoreCase(project.getId(), name))
            throw new RepositoryConflictException("A column with this name already exists");
        column.setName(name); return ProjectColumnResponse.from(column);
    }

    @Transactional
    public void deleteColumn(Long columnId, Long userId) {
        ProjectColumn existing = columns.findById(columnId).orElseThrow(() -> new ResourceNotFoundException("Project column not found"));
        Project project = lockProject(existing.getProject().getId(), userId); requireManage(project, userId); requireActive(project);
        ProjectColumn column = columns.lockById(columnId).orElseThrow(() -> new ResourceNotFoundException("Project column not found"));
        entityManager.refresh(column, LockModeType.PESSIMISTIC_WRITE);
        if (cards.countByColumn_Id(columnId) != 0) throw new RepositoryConflictException("Move or delete the column's cards before deleting it");
        columns.delete(column); columns.flush(); normalizeColumns(project.getId());
    }

    @Transactional
    public List<ProjectColumnResponse> reorderColumns(Long projectId, ColumnOrderRequest request, Long userId) {
        Project project = lockProject(projectId, userId); requireManage(project, userId); requireActive(project);
        List<ProjectColumn> ordered = columns.findByProject_IdOrderByPositionAscIdAsc(projectId);
        validateCompleteOrder(ordered.stream().map(ProjectColumn::getId).toList(), request.columnIds(), "columns");
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).setPosition(TEMP_POSITION + i);
        columns.flush();
        Map<Long, ProjectColumn> byId = ordered.stream().collect(Collectors.toMap(ProjectColumn::getId, Function.identity()));
        for (int i = 0; i < request.columnIds().size(); i++) byId.get(request.columnIds().get(i)).setPosition(i);
        return columns.findByProject_IdOrderByPositionAscIdAsc(projectId).stream().map(ProjectColumnResponse::from).toList();
    }

    @Transactional
    public ProjectCardResponse createCard(Long projectId, CreateProjectCardRequest request, Long userId) {
        Project project = lockProject(projectId, userId); requireCardWrite(project, userId); requireActive(project);
        ProjectColumn column = columns.findById(request.columnId()).orElseThrow(() -> new ResourceNotFoundException("Project column not found"));
        if (!column.getProject().getId().equals(projectId)) throw new InvalidRequestException("Column belongs to another project");
        List<ProjectCard> inColumn = cards.findByColumn_IdOrderByPositionAscIdAsc(column.getId());
        if (inColumn.size() >= MAX_CARDS_PER_COLUMN) throw new InvalidRequestException("A column can contain at most " + MAX_CARDS_PER_COLUMN + " cards");
        Issue issue = null; PullRequest pullRequest = null; String title; String description;
        if (request.cardType() == ProjectCardType.NOTE) {
            if (request.issueId() != null || request.pullRequestId() != null) throw new InvalidRequestException("Note cards cannot link to issues or pull requests");
            title = requireTitle(request.title()); description = request.description();
        } else if (request.cardType() == ProjectCardType.ISSUE) {
            if (request.issueId() == null || request.pullRequestId() != null) throw new InvalidRequestException("Issue cards require only issueId");
            issue = issues.findById(request.issueId()).orElseThrow(() -> new ResourceNotFoundException("Issue not found"));
            requireLinkedRepository(project, issue.getRepository(), userId);
            if (cards.existsByProject_IdAndIssue_Id(projectId, issue.getId())) throw new RepositoryConflictException("This issue already has a card in the project");
            title = issue.getTitle(); description = request.description();
        } else if (request.cardType() == ProjectCardType.PULL_REQUEST) {
            if (request.pullRequestId() == null || request.issueId() != null) throw new InvalidRequestException("Pull request cards require only pullRequestId");
            pullRequest = pullRequests.findDetailedById(request.pullRequestId()).orElseThrow(() -> new ResourceNotFoundException("Pull request not found"));
            requireLinkedRepository(project, pullRequest.getRepository(), userId);
            if (cards.existsByProject_IdAndPullRequest_Id(projectId, pullRequest.getId())) throw new RepositoryConflictException("This pull request already has a card in the project");
            title = pullRequest.getTitle(); description = request.description();
        } else throw new InvalidRequestException("Unsupported card type");
        ProjectCard card = cards.saveAndFlush(new ProjectCard(project, column, title, description, inColumn.size(), request.cardType(), issue, pullRequest));
        if (project.getRepository() != null) activity.record(project.getRepository(), userId, RepositoryActivityEventType.PROJECT_CARD_CREATED,
                "PROJECT_CARD", card.getId(), "Added a card to project " + project.getName(), "project-card-created:" + card.getId());
        return ProjectCardResponse.from(card);
    }

    @Transactional(readOnly = true)
    public ProjectCardPageResponse listCards(Long projectId, int page, int size, Long userId) {
        readableProject(projectId, userId); validatePage(page, size);
        Page<ProjectCard> result = cards.pageByProject(projectId, PageRequest.of(page, size, Sort.unsorted()));
        result.getContent().forEach(card -> validateLinkedAccess(card, userId));
        return new ProjectCardPageResponse(result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(), result.hasNext(), result.getContent().stream().map(ProjectCardResponse::from).toList());
    }

    @Transactional(readOnly = true)
    public ProjectBoardResponse board(Long projectId, Long userId) {
        Project project = readableProject(projectId, userId);
        List<ProjectCard> all = cards.pageByProject(projectId, PageRequest.of(0, MAX_BOARD_CARDS + 1, Sort.unsorted())).getContent();
        if (all.size() > MAX_BOARD_CARDS) throw new InvalidRequestException("Board contains more than " + MAX_BOARD_CARDS + " cards; use the paginated cards endpoint");
        all.forEach(card -> validateLinkedAccess(card, userId));
        Map<Long, List<ProjectCardResponse>> grouped = all.stream().collect(Collectors.groupingBy(c -> c.getColumn().getId(), LinkedHashMap::new, Collectors.mapping(ProjectCardResponse::from, Collectors.toList())));
        List<ProjectBoardResponse.ColumnBoard> result = columns.findByProject_IdOrderByPositionAscIdAsc(projectId).stream()
                .map(c -> new ProjectBoardResponse.ColumnBoard(ProjectColumnResponse.from(c), grouped.getOrDefault(c.getId(), List.of()))).toList();
        return new ProjectBoardResponse(ProjectResponse.from(project), result);
    }

    @Transactional
    public ProjectCardResponse updateCard(Long cardId, UpdateProjectCardRequest request, Long userId) {
        ProjectCard existing = cards.findById(cardId).orElseThrow(() -> new ResourceNotFoundException("Project card not found"));
        Project project = lockProject(existing.getProject().getId(), userId); requireCardWrite(project, userId); requireActive(project);
        ProjectCard card = cards.lockById(cardId).orElseThrow(() -> new ResourceNotFoundException("Project card not found"));
        entityManager.refresh(card, LockModeType.PESSIMISTIC_WRITE);
        validateLinkedAccess(card, userId);
        if (card.getCardType() != ProjectCardType.NOTE) throw new InvalidRequestException("Only note cards can be edited");
        if (request.title() == null && request.description() == null) throw new InvalidRequestException("At least one card field is required");
        if (request.title() != null) card.setTitle(requireTitle(request.title()));
        if (request.description() != null) card.setDescription(request.description());
        return ProjectCardResponse.from(card);
    }

    @Transactional
    public void deleteCard(Long cardId, Long userId) {
        ProjectCard existing = cards.findById(cardId).orElseThrow(() -> new ResourceNotFoundException("Project card not found"));
        Project project = lockProject(existing.getProject().getId(), userId); requireCardWrite(project, userId); requireActive(project);
        ProjectCard card = cards.lockById(cardId).orElseThrow(() -> new ResourceNotFoundException("Project card not found"));
        entityManager.refresh(card, LockModeType.PESSIMISTIC_WRITE);
        Long columnId = card.getColumn().getId(); cards.delete(card); cards.flush(); normalizeCards(columnId);
    }

    @Transactional
    public ProjectCardResponse moveCard(Long cardId, MoveProjectCardRequest request, Long userId) {
        ProjectCard existing = cards.findById(cardId).orElseThrow(() -> new ResourceNotFoundException("Project card not found"));
        Project project = lockProject(existing.getProject().getId(), userId); requireCardWrite(project, userId); requireActive(project);
        ProjectCard moving = cards.lockById(cardId).orElseThrow(() -> new ResourceNotFoundException("Project card not found"));
        entityManager.refresh(moving, LockModeType.PESSIMISTIC_WRITE);
        validateLinkedAccess(moving, userId);
        ProjectColumn destination = columns.findById(request.columnId()).orElseThrow(() -> new ResourceNotFoundException("Project column not found"));
        if (!destination.getProject().getId().equals(project.getId())) throw new InvalidRequestException("Column belongs to another project");
        Long sourceId = moving.getColumn().getId(); Long destinationId = destination.getId();
        List<ProjectCard> source = cards.findByColumn_IdOrderByPositionAscIdAsc(sourceId);
        List<ProjectCard> target = sourceId.equals(destinationId) ? source : cards.findByColumn_IdOrderByPositionAscIdAsc(destinationId);
        source.removeIf(c -> c.getId().equals(cardId));
        if (target == source) target = source;
        else target.removeIf(c -> c.getId().equals(cardId));
        int position = request.position() == null ? target.size() : request.position();
        if (position < 0 || position > target.size()) throw new InvalidRequestException("position must be between 0 and the destination card count");
        target.add(position, moving);
        stageCards(sourceId.equals(destinationId) ? target : source);
        if (!sourceId.equals(destinationId)) stageCards(target.stream().filter(c -> c != moving).toList());
        moving.setColumn(destination); moving.setPosition(TEMP_POSITION + target.size()); cards.flush();
        normalizeCardSequence(target);
        if (!sourceId.equals(destinationId)) normalizeCardSequence(source);
        if (project.getRepository() != null) activity.record(project.getRepository(), userId, RepositoryActivityEventType.PROJECT_CARD_MOVED,
                "PROJECT_CARD", moving.getId(), "Moved a card in project " + project.getName(), null);
        return ProjectCardResponse.from(moving);
    }

    @Transactional
    public List<ProjectCardResponse> reorderCards(Long projectId, CardOrderRequest request, Long userId) {
        Project project = lockProject(projectId, userId); requireCardWrite(project, userId); requireActive(project);
        ProjectColumn column = columns.findById(request.columnId()).orElseThrow(() -> new ResourceNotFoundException("Project column not found"));
        if (!column.getProject().getId().equals(projectId)) throw new InvalidRequestException("Column belongs to another project");
        List<ProjectCard> ordered = cards.findByColumn_IdOrderByPositionAscIdAsc(column.getId());
        validateCompleteOrder(ordered.stream().map(ProjectCard::getId).toList(), request.cardIds(), "cards");
        ordered.forEach(c -> validateLinkedAccess(c, userId));
        Map<Long, ProjectCard> byId = ordered.stream().collect(Collectors.toMap(ProjectCard::getId, Function.identity()));
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).setPosition(TEMP_POSITION + i);
        cards.flush();
        for (int i = 0; i < request.cardIds().size(); i++) byId.get(request.cardIds().get(i)).setPosition(i);
        return request.cardIds().stream().map(byId::get).map(ProjectCardResponse::from).toList();
    }

    private Project lockProject(Long id, Long userId) {
        Project project = projects.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        authorizeRead(project, userId);
        return project;
    }
    private Project readableProject(Long id, Long userId) {
        Project project = projects.findById(id).orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        authorizeRead(project, userId); return project;
    }
    private boolean isVisible(Project p, Long userId) {
        if (p.getScope() == ProjectScope.PERSONAL) return userId != null && p.getOwner().getId().equals(userId);
        if (p.getRepository().getVisibility() == RepositoryVisibility.PUBLIC) return true;
        if (userId == null) return false;
        try { repositoryAccess.requireReadable(p.getRepository(), userId); return true; }
        catch (ResourceNotFoundException hidden) { return false; }
    }
    private void authorizeRead(Project p, Long userId) {
        if (p.getScope() == ProjectScope.PERSONAL) {
            if (userId == null || !p.getOwner().getId().equals(userId)) throw new ResourceNotFoundException("Project not found");
        } else repositoryAccess.requireReadable(p.getRepository(), userId);
    }
    private void requireManage(Project p, Long userId) {
        if (p.getScope() == ProjectScope.PERSONAL) {
            if (!p.getOwner().getId().equals(userId)) throw new ResourceNotFoundException("Project not found");
        } else repositoryAccess.requireMaintain(p.getRepository(), userId);
    }
    private void requireCardWrite(Project p, Long userId) {
        if (p.getScope() == ProjectScope.PERSONAL) {
            if (!p.getOwner().getId().equals(userId)) throw new ResourceNotFoundException("Project not found");
        } else repositoryAccess.requireWrite(p.getRepository(), userId);
    }
    private void requireLinkedRepository(Project project, Repository linked, Long userId) {
        repositoryAccess.requireReadable(linked, userId);
        if (project.getScope() == ProjectScope.REPOSITORY && !project.getRepository().getId().equals(linked.getId()))
            throw new InvalidRequestException("Linked resource must belong to the project's repository");
    }
    private void validateLinkedAccess(ProjectCard card, Long userId) {
        if (card.getIssue() != null) repositoryAccess.requireReadable(card.getIssue().getRepository(), userId);
        if (card.getPullRequest() != null) repositoryAccess.requireReadable(card.getPullRequest().getRepository(), userId);
    }
    private void requireActive(Project p) { if (p.getStatus() != ProjectStatus.ACTIVE) throw new RepositoryConflictException("Archived projects are read-only"); }
    private void normalizeColumns(Long projectId) {
        List<ProjectColumn> all = columns.findByProject_IdOrderByPositionAscIdAsc(projectId);
        for (int i = 0; i < all.size(); i++) all.get(i).setPosition(TEMP_POSITION + i);
        columns.flush(); for (int i = 0; i < all.size(); i++) all.get(i).setPosition(i);
    }
    private void normalizeCards(Long columnId) { normalizeCardSequence(cards.findByColumn_IdOrderByPositionAscIdAsc(columnId)); }
    private void normalizeCardSequence(List<ProjectCard> sequence) {
        stageCards(sequence); cards.flush(); for (int i = 0; i < sequence.size(); i++) sequence.get(i).setPosition(i);
    }
    private void stageCards(List<ProjectCard> sequence) { for (int i = 0; i < sequence.size(); i++) sequence.get(i).setPosition(TEMP_POSITION + i); cards.flush(); }
    private void validatePage(int page, int size) { if (page < 0 || page > 2_000 || size < 1 || size > 100) throw new InvalidRequestException("Page must be between 0 and 2000 and size between 1 and 100"); }
    private void validateCompleteOrder(List<Long> existing, List<Long> requested, String label) {
        if (requested == null || requested.size() != existing.size() || new HashSet<>(requested).size() != requested.size() || !new HashSet<>(requested).equals(new HashSet<>(existing)))
            throw new InvalidRequestException("Order must contain every " + label + " ID exactly once");
    }
    private String normalizeName(String value) { String result = value == null ? "" : value.trim(); if (result.isEmpty() || result.length() > 120) throw new InvalidRequestException("Project name must contain 1 to 120 characters"); return result; }
    private String normalizeColumnName(String value) { String result = value == null ? "" : value.trim(); if (result.isEmpty() || result.length() > 80) throw new InvalidRequestException("Column name must contain 1 to 80 characters"); return result; }
    private String requireTitle(String value) { String result = value == null ? "" : value.trim(); if (result.isEmpty() || result.length() > 255) throw new InvalidRequestException("Card title must contain 1 to 255 characters"); return result; }
}
