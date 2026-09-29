package com.interview.rag.domain;

/**
 * 文档解析状态机:PENDING → PARSING → SUCCESS / FAILED
 *
 * 面试要点:状态机保证 MQ 重复消费的幂等性——消费者只处理 PENDING 状态的任务
 */
public enum ParseStatus {
    PENDING, PARSING, SUCCESS, FAILED
}
