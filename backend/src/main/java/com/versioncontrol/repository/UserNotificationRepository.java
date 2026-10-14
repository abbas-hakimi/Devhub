package com.versioncontrol.repository;

import com.versioncontrol.entity.UserNotification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {
    boolean existsByRecipient_IdAndDeduplicationKey(Long recipientId, String deduplicationKey);
    @Query("select n.recipient.id from UserNotification n where n.recipient.id in :recipientIds and n.deduplicationKey = :key")
    List<Long> findRecipientIdsWithKey(@Param("recipientIds") List<Long> recipientIds, @Param("key") String key);
    long countByRecipient_Id(Long recipientId);
    @Query("select n from UserNotification n left join fetch n.actor left join fetch n.repository "
            + "where n.recipient.id = :userId and (:unread is null or (:unread = true and n.readAt is null) or (:unread = false and n.readAt is not null)) "
            + "and (n.repository is null or n.repository.visibility = com.versioncontrol.entity.RepositoryVisibility.PUBLIC "
            + "or n.repository.owner.id = :userId or n.repository.id in :repositoryIds)")
    Page<UserNotification> findReadable(@Param("userId") Long userId, @Param("unread") Boolean unread,
            @Param("repositoryIds") List<Long> repositoryIds, Pageable pageable);

    @Query("select count(n) from UserNotification n where n.recipient.id = :userId and n.readAt is null "
            + "and (n.repository is null or n.repository.visibility = com.versioncontrol.entity.RepositoryVisibility.PUBLIC "
            + "or n.repository.owner.id = :userId or n.repository.id in :repositoryIds)")
    long countReadableUnread(@Param("userId") Long userId, @Param("repositoryIds") List<Long> repositoryIds);

    @Query("select n from UserNotification n left join fetch n.actor left join fetch n.repository where n.id = :id and n.recipient.id = :userId")
    Optional<UserNotification> findOwned(@Param("id") Long id, @Param("userId") Long userId);

}
