package com.versioncontrol.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

import com.versioncontrol.entity.Commit;

public interface CommitRepository extends JpaRepository<Commit, Long> {
    long countByRepository_Id(Long repositoryId);

    Optional<Commit> findByCommitHash(String commitHash);

    @EntityGraph(attributePaths = {"repository", "repository.owner", "author", "rootTree", "parentCommit"})
    Optional<Commit> findDetailedByCommitHash(String commitHash);

    interface HistoryRow {
        String getCommitHash();
        String getParentCommitHash();
        String getMessage();
        Long getAuthorId();
        String getAuthorUsername();
        String getRootTreeHash();
        java.time.LocalDateTime getCreatedAt();
        Integer getDepth();
    }

    @Query(value = """
            WITH RECURSIVE commit_chain (commit_id, parent_id, depth) AS (
                SELECT c.id, c.parent_commit_id, 0
                FROM branches b JOIN commits c ON c.id = b.head_commit_id
                WHERE b.repository_id = :repositoryId AND b.name = :branchName
                UNION ALL
                SELECT parent.id, parent.parent_commit_id, chain.depth + 1
                FROM commits parent JOIN commit_chain chain ON parent.id = chain.parent_id
                WHERE chain.depth < :lastDepth
            )
            SELECT c.commit_hash AS commitHash, parent.commit_hash AS parentCommitHash,
                   c.message AS message, author.id AS authorId, author.username AS authorUsername,
                   tree.tree_hash AS rootTreeHash, c.created_at AS createdAt, chain.depth AS depth
            FROM commit_chain chain
            JOIN commits c ON c.id = chain.commit_id
            LEFT JOIN commits parent ON parent.id = c.parent_commit_id
            JOIN users author ON author.id = c.author_id
            JOIN trees tree ON tree.id = c.root_tree_id
            WHERE chain.depth >= :offset AND chain.depth <= :lastDepth
            ORDER BY chain.depth ASC
            """, nativeQuery = true)
    java.util.List<HistoryRow> findHistoryPage(@Param("repositoryId") Long repositoryId,
            @Param("branchName") String branchName, @Param("offset") int offset,
            @Param("lastDepth") int lastDepth);
}
