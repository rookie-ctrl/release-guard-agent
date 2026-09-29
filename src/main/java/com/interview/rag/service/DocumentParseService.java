package com.interview.rag.service;

import com.interview.rag.config.EmbeddingProperties;
import com.interview.rag.domain.DocumentEntity;
import com.interview.rag.domain.ParseStatus;
import com.interview.rag.model.Chunk;
import com.interview.rag.repository.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 文档解析入库(在 MQ 消费者线程中执行)
 *
 * 面试要点:
 * - 解析在消费者线程做,不阻塞用户上传请求(异步削峰)
 * - 状态机 + SUCCESS/PARSING 跳过 → 重复消费幂等
 * - 大文档吞吐:批量向量化(batch) + 有界并行(窗口) + 分批 bulk,千万字从"串行数小时"降到分钟级
 * - 断点续传(parsedChunks):先写 ES 成功再落断点,失败重投从断点继续,已向量化的块不重复付费
 * - 失败不重抛(避免死循环重投),落状态 + 错误信息,由重试端点续传
 */
@Slf4j
@Service
public class DocumentParseService {

    private final DocumentRepository documentRepository;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final VectorIndexService vectorIndexService;
    private final int batchSize;
    private final int parallelism;

    public DocumentParseService(DocumentRepository documentRepository,
                                ChunkingService chunkingService,
                                EmbeddingService embeddingService,
                                VectorIndexService vectorIndexService,
                                EmbeddingProperties embeddingProperties) {
        this.documentRepository = documentRepository;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.vectorIndexService = vectorIndexService;
        this.batchSize = embeddingProperties.batchSize() == null ? 32 : embeddingProperties.batchSize();
        this.parallelism = Math.max(1, embeddingProperties.parallelism() == null ? 4 : embeddingProperties.parallelism());
    }

    public void process(Long documentId) {
        DocumentEntity doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            return;
        }
        // 幂等:SUCCESS 已完成;PARSING 说明另一消费者/重复消息正在处理;
        // PENDING 从头解析,FAILED 从断点(或从头)续传
        if (doc.getStatus() == ParseStatus.SUCCESS || doc.getStatus() == ParseStatus.PARSING) {
            log.warn("文档 {} 状态为 {},跳过重复任务", documentId, doc.getStatus());
            return;
        }

        doc.setStatus(ParseStatus.PARSING);
        doc.setErrorMsg(null);
        documentRepository.save(doc);

        try {
            // 1. 解析文本(PDF / Word / Markdown / txt,Tika 自动识别格式)
            String text = new Tika().parseToString(Path.of(doc.getFilePath()));

            // 2. 分块(同一文件 + 同一算法,重跑结果一致,断点索引才可靠)
            List<String> chunkTexts = chunkingService.chunk(text);

            // 3. 窗口化流水线:批量向量化(窗口内并行)→ 分批 bulk → 落断点
            int start = doc.getParsedChunks() == null ? 0 : doc.getParsedChunks();
            if (start > 0) {
                log.info("文档 {} 从断点继续:已完成 {}/{} 块", doc.getDocName(), start, chunkTexts.size());
            }
            int windowSize = batchSize * parallelism; // 每窗口最多 parallelism 个批量请求在途
            for (int from = start; from < chunkTexts.size(); from += windowSize) {
                int to = Math.min(from + windowSize, chunkTexts.size());

                // 3.1 窗口内并发提交批量向量化请求
                List<CompletableFuture<List<float[]>>> futures = new ArrayList<>();
                for (int i = from; i < to; i += batchSize) {
                    List<String> batch = chunkTexts.subList(i, Math.min(i + batchSize, to));
                    futures.add(embeddingService.embedBatchAsync(batch));
                }

                // 3.2 按提交顺序收集(而非完成顺序),保证 chunkIndex 与原文顺序一致
                List<Chunk> windowChunks = new ArrayList<>(to - from);
                int idx = from;
                for (CompletableFuture<List<float[]>> future : futures) {
                    List<float[]> vectors = future.join();
                    for (float[] vector : vectors) {
                        windowChunks.add(new Chunk(chunkTexts.get(idx++), vector));
                    }
                }

                // 3.3 分批写入 ES(_id 固定,断点重跑幂等覆盖)
                vectorIndexService.indexChunks(String.valueOf(doc.getId()), doc.getDocName(),
                        doc.getDocumentType() == null ? "GENERAL" : doc.getDocumentType().name(),
                        doc.getServiceName(), windowChunks, from);

                // 3.4 断点落库:先写 ES 成功再记进度,保证断点 ≤ 实际已入库块数
                doc.setParsedChunks(from + windowChunks.size());
                documentRepository.save(doc);
                log.info("文档 {} 入库进度: {}/{} 块", doc.getDocName(), from + windowChunks.size(), chunkTexts.size());
            }

            doc.setChunkCount(chunkTexts.size());
            doc.setParsedChunks(null);
            doc.setStatus(ParseStatus.SUCCESS);
            log.info("文档 {} 解析完成,共 {} 块", doc.getDocName(), chunkTexts.size());
        } catch (Exception e) {
            int done = doc.getParsedChunks() == null ? 0 : doc.getParsedChunks();
            log.error("文档 {} 解析失败(已完成 {} 块,重试将从断点续传)", doc.getDocName(), done, e);
            doc.setStatus(ParseStatus.FAILED);
            doc.setErrorMsg(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        documentRepository.save(doc);
    }
}
