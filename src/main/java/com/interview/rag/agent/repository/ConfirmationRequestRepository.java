package com.interview.rag.agent.repository;

import com.interview.rag.agent.domain.ConfirmationRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConfirmationRequestRepository extends JpaRepository<ConfirmationRequest, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select confirmation from ConfirmationRequest confirmation where confirmation.id = :id")
    java.util.Optional<ConfirmationRequest> findLockedById(@Param("id") String id);
}
