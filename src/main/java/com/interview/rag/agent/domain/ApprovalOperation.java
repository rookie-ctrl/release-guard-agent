package com.interview.rag.agent.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "t_approval_operation", uniqueConstraints = @UniqueConstraint(
        name = "uk_approval_operation_confirmation", columnNames = "confirmationId"))
@Getter @Setter @NoArgsConstructor
public class ApprovalOperation {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 36) private String confirmationId;
    @Column(nullable = false, length = 36) private String runId;
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String argumentsJson;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ApprovalOperationStatus status;
    @Column(length = 36) private String attemptToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime nextAttemptAt;
    private int attemptCount;
    @Column(length = 100) private String externalApprovalId;
    @Column(columnDefinition = "LONGTEXT") private String resultJson;
    @Column(columnDefinition = "TEXT") private String errorMessage;
    @CreationTimestamp private LocalDateTime createdAt;
    @UpdateTimestamp private LocalDateTime updatedAt;

    public String externalKey() { return "releaseguard:approval:" + id; }
}
