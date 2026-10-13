package com.interview.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.config.CacheProperties;
import com.interview.rag.security.PromptSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class SemanticCacheSecurityTest {
    @Test
    @SuppressWarnings("unchecked")
    void doesNotReadAnswersCreatedUnderLegacyPolicy() {
        var redis = mock(StringRedisTemplate.class);
        ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        var cache = new SemanticCacheService(redis, new ObjectMapper(), new CacheProperties(true, 0.9, 100));
        assertThat(cache.lookup(new float[]{1, 0})).isNull();
        verify(lists).range("rag:qa:cache:" + PromptSecurity.VERSION, 0, -1);
        verify(lists, never()).range("rag:qa:cache", 0, -1);
    }
}
