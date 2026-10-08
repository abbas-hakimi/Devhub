package com.versioncontrol.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.versioncontrol.entity.TreeEntry;

public interface TreeEntryRepository extends JpaRepository<TreeEntry, Long> {
    @EntityGraph(attributePaths = {"blob", "childTree"})
    List<TreeEntry> findByTree_IdOrderByNameAsc(Long treeId);
}
