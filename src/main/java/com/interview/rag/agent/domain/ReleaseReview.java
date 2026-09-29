package com.interview.rag.agent.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "t_release_review", uniqueConstraints = @UniqueConstraint(
        name = "uk_release_review_session", columnNames = "sessionId"))
@Getter
@Setter
@NoArgsConstructor
public class ReleaseReview {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 36)
    private String sessionId;

    @Column(length = 100)
    private String serviceName;

    @Column(length = 50)
    private String releaseVersion;

    @Column(length = 20)
    private String riskLevel;

    @Column(columnDefinition = "LONGTEXT")
    private String contextJson;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
