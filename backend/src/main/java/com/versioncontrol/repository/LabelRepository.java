package com.versioncontrol.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import com.versioncontrol.entity.Label;

public interface LabelRepository extends JpaRepository<Label, Long> {
    @Override @EntityGraph(attributePaths = {"repository", "repository.owner"})
    Optional<Label> findById(Long id);
    @EntityGraph(attributePaths = {"repository", "repository.owner"})
    List<Label> findByRepository_IdOrderByNameAsc(Long repositoryId);
    boolean existsByRepository_IdAndNameIgnoreCase(Long repositoryId, String name);
    boolean existsByRepository_IdAndNameIgnoreCaseAndIdNot(Long repositoryId, String name, Long id);
}
