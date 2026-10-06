package com.versioncontrol.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.RepositoryVisibility;
import java.util.List;

public interface RepositoryRepository extends JpaRepository<Repository, Long> {

    boolean existsByOwner_IdAndNameIgnoreCase(Long ownerId, String name);

    boolean existsByOwner_IdAndName(Long ownerId, String name);

    boolean existsByOwner_IdAndNameIgnoreCaseAndIdNot(Long ownerId, String name, Long id);

    List<Repository> findByVisibilityOrderByCreatedAtDesc(RepositoryVisibility visibility);

    List<Repository> findByVisibilityAndOwner_UsernameIgnoreCaseOrderByCreatedAtDesc(RepositoryVisibility visibility, String username);

    List<Repository> findByVisibilityAndNameContainingIgnoreCaseOrderByCreatedAtDesc(RepositoryVisibility visibility, String name);

    List<Repository> findByVisibilityAndOwner_UsernameIgnoreCaseAndNameContainingIgnoreCaseOrderByCreatedAtDesc(
            RepositoryVisibility visibility, String username, String name);

    List<Repository> findByOwner_IdOrderByCreatedAtDesc(Long ownerId);
}

