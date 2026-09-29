package com.interview.rag.model;

/**
 * 答案引用出处(防幻觉:每个答案都告诉用户信息来自哪份文档的哪一段)
 */
public record Reference(String docName, int chunkIndex, String snippet) {
}
