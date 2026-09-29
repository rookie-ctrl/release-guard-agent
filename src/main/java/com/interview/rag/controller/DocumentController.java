package com.interview.rag.controller;

import com.interview.rag.domain.DocumentEntity;
import com.interview.rag.domain.DocumentType;
import com.interview.rag.domain.ParseStatus;
import com.interview.rag.exception.ResourceNotFoundException;
import com.interview.rag.mq.ParseMessageProducer;
import com.interview.rag.repository.DocumentRepository;
import com.interview.rag.service.VectorIndexService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

/**
 * 文档管理:上传(触发异步解析)、列表、状态查询(轮询)、删除
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentRepository documentRepository;
    private final ParseMessageProducer parseMessageProducer;
    private final VectorIndexService vectorIndexService;
    private final String storageDir;

    public DocumentController(DocumentRepository documentRepository,
                              ParseMessageProducer parseMessageProducer,
                              VectorIndexService vectorIndexService,
                              @Value("${rag.storage.dir:./data/docs}") String storageDir) {
        this.documentRepository = documentRepository;
        this.parseMessageProducer = parseMessageProducer;
        this.vectorIndexService = vectorIndexService;
        this.storageDir = storageDir;
    }

    @PostMapping
    public DocumentEntity upload(@RequestParam("file") MultipartFile file,
                                @RequestParam(defaultValue = "GENERAL") DocumentType documentType,
                                @RequestParam(required = false) String serviceName) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        Path dir = Paths.get(storageDir);
        Files.createDirectories(dir);
        String storedName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path target = dir.resolve(storedName);
        file.transferTo(target);

        DocumentEntity doc = new DocumentEntity();
        doc.setDocName(file.getOriginalFilename());
        doc.setDocumentType(documentType);
        doc.setServiceName(serviceName == null || serviceName.isBlank() ? null : serviceName.trim());
        doc.setFilePath(target.toAbsolutePath().toString());
        doc.setFileSize(file.getSize());
        documentRepository.save(doc);

        // 投递 MQ,异步解析入库(状态 PENDING,前端轮询)
        parseMessageProducer.send(doc.getId());
        return doc;
    }

    @GetMapping
    public List<DocumentEntity> list() {
        return documentRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    @GetMapping("/{id}")
    public DocumentEntity get(@PathVariable Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("文档不存在: " + id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) throws IOException {
        DocumentEntity doc = documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("文档不存在: " + id));
        vectorIndexService.deleteByDocId(String.valueOf(id));
        Files.deleteIfExists(Path.of(doc.getFilePath()));
        documentRepository.delete(doc);
    }

    /** 解析失败后重试:重新投递 MQ,消费者从断点(parsedChunks)续传 */
    @PostMapping("/{id}/retry")
    public DocumentEntity retry(@PathVariable Long id) {
        DocumentEntity doc = documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("文档不存在: " + id));
        if (doc.getStatus() == ParseStatus.SUCCESS) {
            throw new IllegalArgumentException("文档已入库,无需重试");
        }
        parseMessageProducer.send(doc.getId());
        return doc;
    }
}
