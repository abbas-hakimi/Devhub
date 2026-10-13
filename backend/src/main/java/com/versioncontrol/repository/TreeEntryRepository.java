package com.versioncontrol.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

import com.versioncontrol.entity.TreeEntry;

public interface TreeEntryRepository extends JpaRepository<TreeEntry, Long> {
    @EntityGraph(attributePaths = {"blob", "childTree"})
    List<TreeEntry> findByTree_IdOrderByNameAsc(Long treeId);

    @EntityGraph(attributePaths = {"blob", "childTree"})
    List<TreeEntry> findByTree_Id(Long treeId);

    @EntityGraph(attributePaths = {"blob", "childTree"})
    Optional<TreeEntry> findByTree_IdAndName(Long treeId, String name);
}
