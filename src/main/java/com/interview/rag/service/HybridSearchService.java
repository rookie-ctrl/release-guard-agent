package com.interview.rag.service;

import com.interview.rag.config.RetrievalProperties;
import com.interview.rag.model.DocFragment;
import com.interview.rag.util.VectorUtil;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * 混合检索:BM25(关键词)+ kNN(向量)双路召回,RRF 融合 + 余弦相似度阈值过滤
 *
 * 面试要点:
 * - 为什么混合:BM25 对专有名词/精确匹配强(倒排索引),向量检索懂语义;
 *   纯向量对新文档/冷门词弱,纯关键词不懂同义改写,两者互补
 * - RRF(倒数排名融合)只关心排名不关心分数,免去两路分数不可比的归一化问题
 * - 相似度过滤在应用层做:ES 返回的向量分数语义不统一,应用层用余弦相似度精确控制阈值
 */
@Service
public class HybridSearchService {

    private static final double RRF_K = 60.0;

    private final VectorIndexService vectorIndexService;
    private final RetrievalProperties properties;

    public HybridSearchService(VectorIndexService vectorIndexService, RetrievalProperties properties) {
        this.vectorIndexService = vectorIndexService;
        this.properties = properties;
    }

    public List<DocFragment> hybridSearch(String question, float[] queryEmbedding) throws IOException {
        return hybridSearch(question, queryEmbedding, null, null);
    }

    public List<DocFragment> hybridSearch(String question, float[] queryEmbedding,
                                          String documentType, String serviceName) throws IOException {
        int candidateSize = properties.topK() * 2;

        // 双路召回
        List<VectorIndexService.HitRecord> bm25Hits = vectorIndexService.searchBm25(
                question, candidateSize, documentType, serviceName);
        List<VectorIndexService.HitRecord> knnHits = vectorIndexService.searchKnn(
                queryEmbedding, candidateSize, documentType, serviceName);

        // RRF 融合:score = Σ 1 / (k + rank)
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, DocFragment> byId = new LinkedHashMap<>();

        int rank = 1;
        for (VectorIndexService.HitRecord hit : bm25Hits) {
            accumulate(hit, rank++, true, rrfScores, byId);
        }
        rank = 1;
        for (VectorIndexService.HitRecord hit : knnHits) {
            accumulate(hit, rank++, false, rrfScores, byId);
        }

        return byId.values().stream()
                // 应用层计算余弦相似度 + 阈值过滤(低于阈值的内容宁可不要,防止答非所问)
                .map(f -> new DocFragment(
                        f.id(), f.docId(), f.docName(), f.chunkIndex(), f.content(), f.embedding(),
                        f.bm25Score(), f.knnScore(), f.rrfScore(),
                        VectorUtil.cosine(VectorUtil.toDoubles(queryEmbedding), VectorUtil.toDoubles(f.embedding()))))
                .filter(f -> f.similarity() >= properties.similarityThreshold())
                .sorted(Comparator.comparing(DocFragment::rrfScore).reversed())
                .limit(properties.topK())
                .toList();
    }

    private void accumulate(VectorIndexService.HitRecord hit, int rank, boolean fromBm25,
                            Map<String, Double> rrfScores, Map<String, DocFragment> byId) {
        double contribution = 1.0 / (RRF_K + rank);
        rrfScores.merge(hit.id(), contribution, Double::sum);

        DocFragment existing = byId.get(hit.id());
        DocFragment fragment;
        if (existing == null) {
            fragment = new DocFragment(
                    hit.id(), hit.docId(), hit.docName(), hit.chunkIndex(), hit.content(), hit.embedding(),
                    fromBm25 ? hit.score() : null,
                    fromBm25 ? null : hit.score(),
                    rrfScores.get(hit.id()),
                    0.0);
        } else {
            fragment = new DocFragment(
                    existing.id(), existing.docId(), existing.docName(), existing.chunkIndex(),
                    existing.content(), existing.embedding(),
                    fromBm25 ? hit.score() : existing.bm25Score(),
                    fromBm25 ? existing.knnScore() : hit.score(),
                    rrfScores.get(hit.id()),
                    existing.similarity());
        }
        byId.put(hit.id(), fragment);
    }
}
