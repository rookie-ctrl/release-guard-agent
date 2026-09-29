package com.interview.rag.model;

/**
 * 文本分块 + 其向量表示
 */
public record Chunk(String text, float[] embedding) {
}
