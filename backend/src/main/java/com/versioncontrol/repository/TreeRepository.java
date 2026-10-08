package com.versioncontrol.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.versioncontrol.entity.Tree;

public interface TreeRepository extends JpaRepository<Tree, Long> {
    Optional<Tree> findByTreeHash(String treeHash);

    long countByTreeHash(String treeHash);
}
