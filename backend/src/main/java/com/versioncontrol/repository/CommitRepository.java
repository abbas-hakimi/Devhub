package com.versioncontrol.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.versioncontrol.entity.Commit;

public interface CommitRepository extends JpaRepository<Commit, Long> {
    long countByRepository_Id(Long repositoryId);
}
