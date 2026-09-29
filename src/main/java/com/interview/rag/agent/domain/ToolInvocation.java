package com.interview.rag.agent.domain;

import com.interview.rag.agent.tool.ToolRiskLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "t_tool_invocation", uniqueConstraints = @UniqueConstraint(
        name = "uk_tool_invocation_idempotency", columnNames = "idempotencyKey"))
@Getter
@Setter
@NoArgsConstructor
public class ToolInvocation {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 36)
    private String runId;

    @Column(nullable = false, length = 100)
    private String toolName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ToolRiskLevel riskLevel;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String argumentsJson;

    @Column(columnDefinition = "LONGTEXT")
    private String resultJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExecutionStatus status;

    @Column(nullable = false, length = 64)
    private String idempotencyKey;

    private Integer retryCount;

    private Long durationMs;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
