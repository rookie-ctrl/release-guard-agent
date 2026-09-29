package com.interview.rag.controller;

import com.interview.rag.domain.QaRecord;
import com.interview.rag.model.StatsResponse;
import com.interview.rag.repository.DocumentRepository;
import com.interview.rag.repository.QaRecordRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 用量统计:今日问答数、token 消耗、缓存命中率(成本可视化)
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final QaRecordRepository qaRecordRepository;
    private final DocumentRepository documentRepository;

    public StatsController(QaRecordRepository qaRecordRepository, DocumentRepository documentRepository) {
        this.qaRecordRepository = qaRecordRepository;
        this.documentRepository = documentRepository;
    }

    @GetMapping
    public StatsResponse stats() {
        LocalDate today = LocalDate.now();
        List<QaRecord> records = qaRecordRepository.findByCreatedAtBetween(
                today.atStartOfDay(), today.plusDays(1).atStartOfDay());

        long totalTokens = records.stream()
                .mapToLong(r -> r.getTotalTokens() == null ? 0 : r.getTotalTokens())
                .sum();
        long cacheHits = records.stream()
                .filter(r -> Boolean.TRUE.equals(r.getCacheHit()))
                .count();
        double hitRate = records.isEmpty() ? 0 : (double) cacheHits / records.size();

        return new StatsResponse(records.size(), totalTokens, cacheHits, hitRate,
                documentRepository.count());
    }
}
