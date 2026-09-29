package com.interview.rag.agent.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "t_agent_step", uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_step_run_sequence", columnNames = {"runId", "sequenceNumber"}))
@Getter
@Setter
@NoArgsConstructor
public class AgentStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String runId;

    @Column(nullable = false)
    private Integer sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AgentStepType stepType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExecutionStatus status;

    @Column(columnDefinition = "LONGTEXT")
    private String inputJson;

    @Column(columnDefinition = "LONGTEXT")
    private String outputJson;

    private Long durationMs;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
