package com.interview.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 8.x 客户端装配
 *
 * 面试要点:这里用的是官方 Java API Client(不是 spring-data-elasticsearch),
 * 因为 kNN 向量检索需要对底层 query 的完全控制。
 */
@Configuration
public class ElasticsearchConfig {

    @Bean
    public ElasticsearchClient elasticsearchClient(@Value("${ES_HOST:localhost}") String esHost) {
        RestClient restClient = RestClient.builder(
                new HttpHost(esHost, 9200, "http")).build();
        return new ElasticsearchClient(
                new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }
}
