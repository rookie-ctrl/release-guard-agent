package com.interview.rag.model;

/**
 * 检索命中的文档片段
 *
 * @param id         ES 文档 id
 * @param docId      所属文档(对应 t_document 主键)
 * @param docName    文档名(用于溯源展示)
 * @param chunkIndex 片段在文档中的序号
 * @param content    片段原文
 * @param embedding  片段向量(用于应用层精确计算余弦相似度)
 * @param bm25Score  BM25 得分
 * @param knnScore   kNN 得分
 * @param rrfScore   RRF 融合得分
 * @param similarity 与查询的余弦相似度(阈值过滤用)
 */
public record DocFragment(
        String id,
        String docId,
        String docName,
        int chunkIndex,
        String content,
        float[] embedding,
        Double bm25Score,
        Double knnScore,
        Double rrfScore,
        Double similarity
) {
}
