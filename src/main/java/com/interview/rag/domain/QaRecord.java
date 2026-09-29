package com.interview.rag.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 问答记录(用于 token 成本统计与缓存命中率分析)
 */
@Entity
@Table(name = "t_qa_record")
@Getter
@Setter
@NoArgsConstructor
public class QaRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String question;

    private Long inputTokens;

    private Long outputTokens;

    private Long totalTokens;

    /** 是否命中语义缓存(命中则不消耗 token) */
    private Boolean cacheHit;

    /** 本次问答耗时(毫秒) */
    private Long elapsedMs;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
