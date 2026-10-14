package com.versioncontrol.repository;

import com.versioncontrol.entity.RepositoryActivity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositoryActivityRepository extends JpaRepository<RepositoryActivity, Long> {
    boolean existsByRepository_IdAndDeduplicationKey(Long repositoryId, String deduplicationKey);
    long countByRepository_Id(Long repositoryId);
    Page<RepositoryActivity> findByRepository_Id(Long repositoryId, Pageable pageable);
}
