package com.ai.receptionist.repository;

import com.ai.receptionist.entity.CallSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CallSessionRepository extends JpaRepository<CallSession, Long> {

    Optional<CallSession> findByTwilioCallSid(String twilioCallSid);

    @Query("SELECT COUNT(c) FROM CallSession c WHERE c.startedAt >= :since AND (:tenantId IS NULL OR c.tenantId = :tenantId)")
    long countSince(@Param("since") Instant since, @Param("tenantId") Long tenantId);

    @Query("SELECT c.outcome, COUNT(c) FROM CallSession c WHERE c.startedAt >= :since AND (:tenantId IS NULL OR c.tenantId = :tenantId) GROUP BY c.outcome")
    List<Object[]> countByOutcome(@Param("since") Instant since, @Param("tenantId") Long tenantId);

    @Query("SELECT COALESCE(AVG(c.durationSeconds), 0) FROM CallSession c WHERE c.startedAt >= :since AND c.durationSeconds IS NOT NULL AND (:tenantId IS NULL OR c.tenantId = :tenantId)")
    double avgDuration(@Param("since") Instant since, @Param("tenantId") Long tenantId);

    @Query("SELECT COUNT(c) FROM CallSession c WHERE c.startedAt >= :since AND c.status = 'FAILED' AND (:tenantId IS NULL OR c.tenantId = :tenantId)")
    long countFailed(@Param("since") Instant since, @Param("tenantId") Long tenantId);
}
