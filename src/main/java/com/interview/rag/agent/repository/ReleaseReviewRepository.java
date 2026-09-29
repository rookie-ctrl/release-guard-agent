package com.interview.rag.agent.repository;

import com.interview.rag.agent.domain.ReleaseReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReleaseReviewRepository extends JpaRepository<ReleaseReview, String> {

    Optional<ReleaseReview> findBySessionId(String sessionId);
}
