package com.interview.rag.agent.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "t_agent_message", indexes = @Index(name = "idx_agent_message_session", columnList = "sessionId,createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class AgentMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String sessionId;

    @Column(length = 36)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    private Integer tokenCount;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
