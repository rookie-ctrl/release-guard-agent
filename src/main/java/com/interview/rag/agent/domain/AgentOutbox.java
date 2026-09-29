package com.interview.rag.agent.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "t_agent_outbox", indexes = @Index(name = "idx_agent_outbox_pending", columnList = "status,nextAttemptAt"))
@Getter
@Setter
@NoArgsConstructor
public class AgentOutbox {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 36)
    private String aggregateId;

    @Column(nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private Integer attemptCount;

    @Column(nullable = false)
    private LocalDateTime nextAttemptAt;

    private LocalDateTime publishedAt;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
