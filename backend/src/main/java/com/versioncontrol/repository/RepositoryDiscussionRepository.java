package com.versioncontrol.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.versioncontrol.entity.RepositoryDiscussion;

public interface RepositoryDiscussionRepository extends JpaRepository<RepositoryDiscussion, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from RepositoryDiscussion d join fetch d.repository where d.id = :id")
    Optional<RepositoryDiscussion> findByIdForUpdate(@Param("id") Long id);
    @Query("select coalesce(max(d.number), 0) from RepositoryDiscussion d where d.repository.id = :repositoryId")
    long maxNumber(@Param("repositoryId") Long repositoryId);
    @Query(value = "select d from RepositoryDiscussion d join fetch d.author join fetch d.repository where d.repository.id = :repositoryId",
           countQuery = "select count(d) from RepositoryDiscussion d where d.repository.id = :repositoryId")
    Page<RepositoryDiscussion> pageByRepository(@Param("repositoryId") Long repositoryId, Pageable pageable);
    @Query("select d from RepositoryDiscussion d join fetch d.author join fetch d.repository where d.id = :id")
    Optional<RepositoryDiscussion> findDetailedById(@Param("id") Long id);
}
