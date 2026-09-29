package com.interview.rag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.interview.rag.config.EmbeddingProperties;
import com.interview.rag.model.Chunk;
import com.interview.rag.util.VectorUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ES 向量索引:索引创建、块写入、关键词/向量两路检索、按文档删除
 *
 * 面试要点:
 * - dense_vector 字段 + cosine 相似度,用于 kNN 检索
 * - content 字段走 text 类型(倒排索引),用于 BM25 检索
 * - 一个索引同时服务两路检索,是混合检索(hybrid search)的基础
 */
@Slf4j
@Service
public class VectorIndexService {

    public static final String INDEX = "kb_chunks";

    private final ElasticsearchClient client;
    private final int dims;

    public VectorIndexService(ElasticsearchClient client, EmbeddingProperties embeddingProperties) {
        this.client = client;
        this.dims = embeddingProperties.dimension();
    }

    @PostConstruct
    public void ensureIndex() {
        try {
            if (!client.indices().exists(e -> e.index(INDEX)).value()) {
                client.indices().create(c -> c.index(INDEX)
                        .mappings(m -> m
                                .properties("embedding", p -> p.denseVector(dv -> dv
                                        .dims(dims)
                                        // ES 8.17 客户端该参数是字符串,取值 cosine/l2_norm/dot_product
                                        .similarity("cosine")))
                                .properties("content", p -> p.text(t -> t))
                                .properties("docId", p -> p.keyword(k -> k))
                                .properties("docName", p -> p.keyword(k -> k))
                                .properties("documentType", p -> p.keyword(k -> k))
                                .properties("serviceName", p -> p.keyword(k -> k))
                                .properties("chunkIndex", p -> p.integer(i -> i))
                        ));
                log.info("ES 索引 {} 创建成功,向量维度 {}", INDEX, dims);
            } else {
                client.indices().putMapping(p -> p.index(INDEX)
                        .properties("documentType", property -> property.keyword(k -> k))
                        .properties("serviceName", property -> property.keyword(k -> k)));
            }
        } catch (Exception e) {
            // 不阻塞启动:ES 未就绪时提示,后续操作会给出明确报错
            log.warn("ES 初始化失败(请先 docker compose up -d 启动 Elasticsearch): {}", e.getMessage());
        }
    }

    /**
     * 分批写入一个文档的块(startIndex 为全局偏移,支持断点续传分窗口写)
     *
     * _id = {docId}_{全局 chunkIndex} 固定:断点重跑时同批块幂等覆盖,不会产生重复数据
     */
    public void indexChunks(String docId, String docName, List<Chunk> chunks, int startIndex) throws IOException {
        indexChunks(docId, docName, "GENERAL", null, chunks, startIndex);
    }

    public void indexChunks(String docId, String docName, String documentType, String serviceName,
                            List<Chunk> chunks, int startIndex) throws IOException {
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            int globalIndex = startIndex + i;
            Map<String, Object> doc = new HashMap<>();
            doc.put("docId", docId);
            doc.put("docName", docName);
            doc.put("documentType", documentType);
            if (serviceName != null && !serviceName.isBlank()) {
                doc.put("serviceName", serviceName);
            }
            doc.put("chunkIndex", globalIndex);
            doc.put("content", chunk.text());
            doc.put("embedding", VectorUtil.toDoubleList(chunk.embedding()));
            bulk.operations(op -> op.index(ix -> ix
                    .index(INDEX)
                    .id(docId + "_" + globalIndex)
                    .document(doc)));
        }
        client.bulk(bulk.build());
        log.info("文档 {} 写入 ES {} 块(偏移 {})", docName, chunks.size(), startIndex);
    }

    /** 关键词检索(BM25,倒排索引) */
    public List<HitRecord> searchBm25(String query, int size) throws IOException {
        return searchBm25(query, size, null, null);
    }

    public List<HitRecord> searchBm25(String query, int size, String documentType, String serviceName)
            throws IOException {
        SearchResponse<Map> response = client.search(s -> s
                        .index(INDEX)
                        .query(q -> q.bool(b -> {
                            b.must(m -> m.match(match -> match.field("content").query(query)));
                            addFilters(b, documentType, serviceName);
                            return b;
                        }))
                        .size(size),
                Map.class);
        return mapHits(response);
    }

    /** 向量检索(kNN,HNSW 近邻图) */
    public List<HitRecord> searchKnn(float[] queryEmbedding, int size) throws IOException {
        return searchKnn(queryEmbedding, size, null, null);
    }

    public List<HitRecord> searchKnn(float[] queryEmbedding, int size, String documentType,
                                    String serviceName) throws IOException {
        SearchResponse<Map> response = client.search(s -> s
                        .index(INDEX)
                        .query(q -> q.knn(k -> {
                            k.field("embedding").queryVector(VectorUtil.toFloatList(queryEmbedding))
                                    .k(size).numCandidates(100);
                            if (documentType != null && !documentType.isBlank()) {
                                k.filter(f -> f.term(t -> t.field("documentType").value(documentType)));
                            }
                            if (serviceName != null && !serviceName.isBlank()) {
                                k.filter(f -> f.term(t -> t.field("serviceName").value(serviceName)));
                            }
                            return k;
                        }))
                        .size(size),
                Map.class);
        return mapHits(response);
    }

    /** 删除某个文档的全部分块(文档删除时清理索引) */
    public void deleteByDocId(String docId) throws IOException {
        client.deleteByQuery(d -> d.index(INDEX)
                .query(q -> q.term(t -> t.field("docId").value(docId))));
        log.info("文档 {} 的索引已清理", docId);
    }

    private List<HitRecord> mapHits(SearchResponse<Map> response) {
        List<HitRecord> records = new ArrayList<>();
        for (Hit<Map> hit : response.hits().hits()) {
            Map<String, Object> source = hit.source();
            if (source == null) {
                continue;
            }
            records.add(new HitRecord(
                    hit.id(),
                    (String) source.get("docId"),
                    (String) source.get("docName"),
                    ((Number) source.get("chunkIndex")).intValue(),
                    (String) source.get("content"),
                    VectorUtil.toFloats((List<?>) source.get("embedding")),
                    hit.score()
            ));
        }
        return records;
    }

    private void addFilters(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder query,
                            String documentType, String serviceName) {
        if (documentType != null && !documentType.isBlank()) {
            query.filter(f -> f.term(t -> t.field("documentType").value(documentType)));
        }
        if (serviceName != null && !serviceName.isBlank()) {
            query.filter(f -> f.term(t -> t.field("serviceName").value(serviceName)));
        }
    }

    /** 检索命中记录 */
    public record HitRecord(
            String id,
            String docId,
            String docName,
            int chunkIndex,
            String content,
            float[] embedding,
            Double score
    ) {
    }
}
