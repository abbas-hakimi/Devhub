package com.versioncontrol.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.versioncontrol.entity.RepositoryStar;

public interface RepositoryStarRepository extends JpaRepository<RepositoryStar, Long> {

    boolean existsByUser_IdAndRepository_Id(Long userId, Long repositoryId);

    boolean existsByRepository_Id(Long repositoryId);
}

