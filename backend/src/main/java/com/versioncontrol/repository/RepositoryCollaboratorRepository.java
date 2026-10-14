package com.versioncontrol.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.versioncontrol.entity.RepositoryCollaborator;

public interface RepositoryCollaboratorRepository extends JpaRepository<RepositoryCollaborator, Long> {
    Optional<RepositoryCollaborator> findByRepository_IdAndUser_Id(Long repositoryId, Long userId);
    Optional<RepositoryCollaborator> findByIdAndUser_Id(Long id, Long userId);
    List<RepositoryCollaborator> findByRepository_IdAndStatusOrderByCreatedAtAsc(Long repositoryId, com.versioncontrol.entity.InvitationStatus status);
    List<RepositoryCollaborator> findByRepository_IdOrderByCreatedAtAsc(Long repositoryId);
    List<RepositoryCollaborator> findByUser_IdAndStatusOrderByCreatedAtDesc(Long userId, com.versioncontrol.entity.InvitationStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from RepositoryCollaborator c where c.id = :id") Optional<RepositoryCollaborator> lockById(@Param("id") Long id);
    @Query("select c.repository.id from RepositoryCollaborator c where c.user.id = :userId and c.status = com.versioncontrol.entity.InvitationStatus.ACCEPTED") List<Long> acceptedRepositoryIds(@Param("userId") Long userId);
    @Query("select c.user.id from RepositoryCollaborator c where c.repository.id = :repositoryId and c.user.id in :userIds and c.status = com.versioncontrol.entity.InvitationStatus.ACCEPTED")
    List<Long> acceptedUserIds(@Param("repositoryId") Long repositoryId, @Param("userIds") List<Long> userIds);
}
