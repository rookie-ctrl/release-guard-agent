package com.interview.rag.agent.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "t_agent_run", indexes = @Index(name = "idx_agent_run_session", columnList = "sessionId"))
@Getter
@Setter
@NoArgsConstructor
public class AgentRun {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 36)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AgentStatus status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String userMessage;

    @Column(columnDefinition = "LONGTEXT")
    private String finalAnswer;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Integer currentStep;

    private Integer toolCallCount;

    private Long inputTokens;

    private Long outputTokens;

    private Long elapsedMs;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
