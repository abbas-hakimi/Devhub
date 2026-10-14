package com.versioncontrol.repository;

import com.versioncontrol.entity.RefreshTokenHistory;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenHistoryRepository extends JpaRepository<RefreshTokenHistory, Long> {
    @Query("select t.session.id from RefreshTokenHistory t where t.tokenHash = :tokenHash")
    Optional<Long> findSessionIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshTokenHistory t join fetch t.session where t.tokenHash = :tokenHash")
    Optional<RefreshTokenHistory> lockByTokenHash(@Param("tokenHash") String tokenHash);

    @Query("select max(t.generation) from RefreshTokenHistory t where t.session.id = :sessionId")
    Integer findMaximumGeneration(@Param("sessionId") Long sessionId);

    @Modifying
    @Query("update RefreshTokenHistory t set t.revokedAt = :now where t.session.id = :sessionId and t.revokedAt is null")
    int revokeAllForSession(@Param("sessionId") Long sessionId, @Param("now") LocalDateTime now);
}
