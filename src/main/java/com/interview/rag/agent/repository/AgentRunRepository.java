package com.interview.rag.agent.repository;

import com.interview.rag.agent.domain.AgentRun;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface AgentRunRepository extends JpaRepository<AgentRun, String> {

    List<AgentRun> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AgentRun r where r.id = :id")
    Optional<AgentRun> findLockedById(@Param("id") String id);

    @Query("select count(r) > 0 from AgentRun r where r.id = :id and r.executionToken = :token "
            + "and r.executionLeaseUntil > :now and r.executionDeadlineAt > :now")
    boolean hasExecution(@Param("id") String id, @Param("token") String token, @Param("now") LocalDateTime now);

    @Modifying
    @Query("update AgentRun r set r.executionLeaseUntil = :until where r.id = :id "
            + "and r.executionToken = :token and r.executionLeaseUntil > :now and r.executionDeadlineAt > :now")
    int renewOwned(@Param("id") String id, @Param("token") String token,
                   @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

    @Modifying
    @Query("""
            update AgentRun r set r.status = :#{#run.status}, r.finalAnswer = :#{#run.finalAnswer},
                r.errorMessage = :#{#run.errorMessage}, r.currentStep = :#{#run.currentStep},
                r.toolCallCount = :#{#run.toolCallCount}, r.inputTokens = :#{#run.inputTokens},
                r.outputTokens = :#{#run.outputTokens}, r.elapsedMs = :#{#run.elapsedMs}, r.updatedAt = :now
            where r.id = :#{#run.id} and r.executionToken = :token
                and r.executionLeaseUntil > :now and r.executionDeadlineAt > :now
            """)
    int updateOwned(@Param("run") AgentRun run, @Param("token") String token, @Param("now") LocalDateTime now);

    @Modifying
    @Query("update AgentRun r set r.executionLeaseUntil = CURRENT_TIMESTAMP "
            + "where r.id = :id and r.executionToken = :token")
    int clearExecution(@Param("id") String id, @Param("token") String token);

    @Query("""
        select r from AgentRun r where
          (r.status = com.interview.rag.agent.domain.AgentStatus.RUNNING
              and (r.executionLeaseUntil <= :now or r.executionDeadlineAt <= :now))
          or (r.status = com.interview.rag.agent.domain.AgentStatus.CREATED and r.updatedAt <= :stale)
        order by r.updatedAt asc
        """)
    List<AgentRun> findRecoverable(@Param("now") LocalDateTime now, @Param("stale") LocalDateTime stale, Pageable page);
}
