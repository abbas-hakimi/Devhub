package com.versioncontrol.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.versioncontrol.entity.Repository;
import com.versioncontrol.entity.RepositoryVisibility;
import java.util.List;

public interface RepositoryRepository extends JpaRepository<Repository, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Repository r join fetch r.owner where r.id = :id")
    java.util.Optional<Repository> findByIdForUpdate(@Param("id") Long id);

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

