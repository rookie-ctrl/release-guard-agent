package com.interview.rag.agent.repository;

import com.interview.rag.agent.domain.ToolInvocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ToolInvocationRepository extends JpaRepository<ToolInvocation, String> {

    Optional<ToolInvocation> findByIdempotencyKey(String idempotencyKey);
}
