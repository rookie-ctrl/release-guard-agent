package com.interview.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.config.CacheProperties;
import com.interview.rag.model.Reference;
import com.interview.rag.util.VectorUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 语义缓存(Redis):相似问题直接返回历史答案,省 token 成本 + 降低延迟
 *
 * 面试要点:
 * - 不是字符串精确匹配,而是 embedding 余弦相似度匹配——"换个问法"也能命中
 * - 阈值是 trade-off:太高命中率低(省不了钱),太低会答非所问(体验差)
 * - 用 Redis List + LTRIM 做容量上限(LRU 近似),避免缓存无限膨胀
 */
@Service
public class SemanticCacheService {

    private static final String KEY = "rag:qa:cache";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CacheProperties properties;

    public SemanticCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                CacheProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public record CachedAnswer(String question, String answer, List<Reference> refs, double similarity) {
    }

    private record Entry(String question, String answer, List<Double> embedding, List<Reference> refs) {
    }

    /**
     * 查找相似问题:返回相似度最高且超过阈值的缓存答案,否则返回 null
     */
    public CachedAnswer lookup(float[] questionEmbedding) {
        if (!Boolean.TRUE.equals(properties.enabled())) {
            return null;
        }
        List<String> entries = redisTemplate.opsForList().range(KEY, 0, -1);
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        double[] query = VectorUtil.toDoubles(questionEmbedding);

        double bestSimilarity = -1;
        Entry bestEntry = null;
        for (String json : entries) {
            try {
                Entry entry = objectMapper.readValue(json, Entry.class);
                double[] cached = entry.embedding().stream().mapToDouble(Double::doubleValue).toArray();
                double similarity = VectorUtil.cosine(query, cached);
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    bestEntry = entry;
                }
            } catch (JsonProcessingException ignored) {
                // 脏数据跳过
            }
        }
        if (bestEntry != null && bestSimilarity >= properties.similarityThreshold()) {
            return new CachedAnswer(bestEntry.question(), bestEntry.answer(), bestEntry.refs(), bestSimilarity);
        }
        return null;
    }

    /**
     * 写入缓存(LPUSH + LTRIM 控制容量)
     */
    public void put(String question, String answer, float[] questionEmbedding, List<Reference> refs) {
        if (!Boolean.TRUE.equals(properties.enabled())) {
            return;
        }
        try {
            Entry entry = new Entry(question, answer, VectorUtil.toDoubleList(questionEmbedding), refs);
            redisTemplate.opsForList().leftPush(KEY, objectMapper.writeValueAsString(entry));
            redisTemplate.opsForList().trim(KEY, 0, properties.maxSize() - 1);
        } catch (JsonProcessingException ignored) {
        }
    }
}
