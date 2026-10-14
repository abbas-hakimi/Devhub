package com.versioncontrol.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.versioncontrol.entity.RepositoryStar;

public interface RepositoryStarRepository extends JpaRepository<RepositoryStar, Long> {

    @org.springframework.data.jpa.repository.Query("select s.user.id from RepositoryStar s where s.repository.id = :repositoryId")
    org.springframework.data.domain.Page<Long> findUserIdsByRepositoryId(
            @org.springframework.data.repository.query.Param("repositoryId") Long repositoryId,
            org.springframework.data.domain.Pageable pageable);

    boolean existsByUser_IdAndRepository_Id(Long userId, Long repositoryId);

    boolean existsByRepository_Id(Long repositoryId);
}

