package com.versioncontrol.service;

import com.versioncontrol.dto.activity.RepositoryActivityPageResponse;
import com.versioncontrol.dto.activity.RepositoryActivityResponse;
import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.RepositoryActivity;
import com.versioncontrol.entity.RepositoryActivityEventType;
import com.versioncontrol.entity.User;
import com.versioncontrol.exception.InvalidRequestException;
import com.versioncontrol.repository.RepositoryActivityRepository;
import com.versioncontrol.repository.UserRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepositoryActivityService {
    private final RepositoryActivityRepository activities;
    private final UserRepository users;
    private final RepositoryAccessService access;

    public RepositoryActivityService(RepositoryActivityRepository activities, UserRepository users, RepositoryAccessService access) {
        this.activities = activities; this.users = users; this.access = access;
    }

    @Transactional
    public void record(Repository repository, Long actorId, RepositoryActivityEventType type,
            String resourceType, Long resourceId, String summary, String dedupeKey) {
        if (dedupeKey != null && activities.existsByRepository_IdAndDeduplicationKey(repository.getId(), dedupeKey)) return;
        User actor = actorId == null ? null : users.findById(actorId).orElse(null);
        activities.save(new RepositoryActivity(repository, actor, type, resourceType, resourceId, safe(summary), dedupeKey));
    }

    @Transactional(readOnly = true)
    public RepositoryActivityPageResponse list(Long repositoryId, int page, int size, Long userId) {
        Repository repository = access.find(repositoryId);
        access.requireReadable(repository, userId);
        if (page < 0 || page > 2_000 || size < 1 || size > 100)
            throw new InvalidRequestException("Page must be between 0 and 2000 and size between 1 and 100");
        Page<RepositoryActivity> result = activities.findByRepository_Id(repositoryId,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        List<RepositoryActivityResponse> items = result.getContent().stream().map(RepositoryActivityResponse::from).toList();
        return new RepositoryActivityPageResponse(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.hasNext(), items);
    }

    private String safe(String value) { return value == null ? "" : value.length() <= 500 ? value : value.substring(0, 500); }
}
