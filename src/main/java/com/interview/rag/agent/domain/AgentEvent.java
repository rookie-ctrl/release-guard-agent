package com.interview.rag.agent.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "t_agent_event", uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_event_run_sequence", columnNames = {"runId", "sequenceNumber"}))
@Getter
@Setter
@NoArgsConstructor
public class AgentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String runId;

    @Column(nullable = false)
    private Long sequenceNumber;

    @Column(nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String dataJson;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
