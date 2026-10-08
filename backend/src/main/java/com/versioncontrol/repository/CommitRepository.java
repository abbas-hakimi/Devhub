package com.versioncontrol.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

import com.versioncontrol.entity.Commit;

public interface CommitRepository extends JpaRepository<Commit, Long> {
    long countByRepository_Id(Long repositoryId);

    Optional<Commit> findByCommitHash(String commitHash);
}
