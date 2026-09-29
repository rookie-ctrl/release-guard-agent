package com.interview.rag.agent.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "t_agent_session")
@Getter
@Setter
@NoArgsConstructor
public class AgentSession {

    @Id
    @Column(length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AgentStatus status;

    @Column(length = 100)
    private String userId;

    @Column(columnDefinition = "TEXT")
    private String summary;

    private Long summaryThroughMessageId;

    @Column(columnDefinition = "TEXT")
    private String pullRequestContextJson;

    @Version
    private Long version;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
