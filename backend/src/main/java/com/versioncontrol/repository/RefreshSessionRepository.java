package com.versioncontrol.repository;

import com.versioncontrol.entity.RefreshSession;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s join fetch s.user where s.id = :id")
    Optional<RefreshSession> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s join fetch s.user where s.id = :id and s.user.id = :userId")
    Optional<RefreshSession> lockByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.user.id = :userId order by s.id")
    List<RefreshSession> lockAllForUser(@Param("userId") Long userId);

    @Query("select s from RefreshSession s join fetch s.user where s.user.id = :userId order by s.createdAt desc, s.id desc")
    List<RefreshSession> listForUser(@Param("userId") Long userId);

    @Modifying
    @Query("delete from RefreshSession s where s.retainedUntil < :now")
    int deleteExpiredHistory(@Param("now") LocalDateTime now);
}
