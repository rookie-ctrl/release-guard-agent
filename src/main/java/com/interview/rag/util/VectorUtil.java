package com.interview.rag.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量工具:余弦相似度与类型转换
 */
public final class VectorUtil {

    private VectorUtil() {
    }

    /**
     * 余弦相似度:两向量夹角余弦,值域 [-1, 1],越接近 1 语义越相近
     */
    public static double cosine(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("向量维度不一致: " + a.length + " vs " + b.length);
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public static double[] toDoubles(float[] v) {
        double[] result = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            result[i] = v[i];
        }
        return result;
    }

    public static List<Double> toDoubleList(float[] v) {
        double[] d = toDoubles(v);
        return java.util.Arrays.stream(d).boxed().toList();
    }

    /** ES Java 客户端 8.17 的 KnnQuery.queryVector 接收 List<Float> */
    public static List<Float> toFloatList(float[] v) {
        List<Float> result = new ArrayList<>(v.length);
        for (float f : v) {
            result.add(f);
        }
        return result;
    }

    /** 从 ES _source 取出的 embedding(JSON 数组)还原为 float[] */
    public static float[] toFloats(List<?> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = ((Number) values.get(i)).floatValue();
        }
        return result;
    }
}
