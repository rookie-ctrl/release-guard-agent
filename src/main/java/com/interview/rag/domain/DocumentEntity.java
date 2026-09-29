package com.interview.rag.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 知识库文档(元信息 + 解析状态)
 */
@Entity
@Table(name = "t_document")
@Getter
@Setter
@NoArgsConstructor
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String docName;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private DocumentType documentType = DocumentType.GENERAL;

    @Column(length = 100)
    private String serviceName;

    @Column(nullable = false)
    private String filePath;

    private Long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParseStatus status = ParseStatus.PENDING;

    /** 解析成功后写入:切分出的块数 */
    private Integer chunkCount;

    /** 断点续传:已成功写入 ES 的块数;解析成功后置空 */
    private Integer parsedChunks;

    @Column(columnDefinition = "TEXT")
    private String errorMsg;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
