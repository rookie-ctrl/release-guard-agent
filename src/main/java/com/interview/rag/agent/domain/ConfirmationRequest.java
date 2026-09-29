package com.interview.rag.agent.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "t_confirmation_request", indexes = @Index(name = "idx_confirmation_run", columnList = "runId"))
@Getter
@Setter
@NoArgsConstructor
public class ConfirmationRequest {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 36)
    private String runId;

    @Column(nullable = false, length = 36)
    private String sessionId;

    @Column(nullable = false, length = 100)
    private String toolName;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String argumentsJson;

    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConfirmationStatus status;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
