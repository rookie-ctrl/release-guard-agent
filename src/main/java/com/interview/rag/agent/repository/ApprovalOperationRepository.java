package com.interview.rag.agent.repository;

import com.interview.rag.agent.domain.ApprovalOperation;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.*;

public interface ApprovalOperationRepository extends JpaRepository<ApprovalOperation, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ApprovalOperation o where o.id = :id")
    Optional<ApprovalOperation> findLockedById(@Param("id") String id);

    @Query("""
        select o from ApprovalOperation o where
            (o.status in (com.interview.rag.agent.domain.ApprovalOperationStatus.PENDING,
                com.interview.rag.agent.domain.ApprovalOperationStatus.UNCERTAIN) and o.nextAttemptAt <= :now)
            or (o.status = com.interview.rag.agent.domain.ApprovalOperationStatus.EXECUTING and o.leaseUntil <= :now)
        order by o.createdAt asc
        """)
    List<ApprovalOperation> findRecoverable(@Param("now") LocalDateTime now, Pageable page);
}
