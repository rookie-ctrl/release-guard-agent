package com.interview.rag.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 文本分块
 *
 * 面试要点:
 * - 为什么分块:大模型上下文有限 + 检索粒度需要(块越小检索越准,但语义越碎)
 * - 为什么 overlap:相邻块之间保留重叠,防止关键信息恰好被切在边界上
 * - 切分策略:优先按段落(空行),段落过长按句号/换行,再长才硬切——保持语义完整
 */
@Service
public class ChunkingService {

    private final int maxChars;
    private final int overlapChars;

    public ChunkingService(@Value("${rag.chunking.max-chars:400}") int maxChars,
                           @Value("${rag.chunking.overlap-chars:40}") int overlapChars) {
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    public List<String> chunk(String text) {
        // 1. 规范化:统一换行、压缩空白
        String normalized = text.replace("\r\n", "\n")
                .replaceAll("[ \t]+", " ")
                .replaceAll("\n{3,}", "\n\n");

        // 2. 按段落切分,段落内超长再降级切分
        List<String> paragraphs = Arrays.stream(normalized.split("\n\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String para : paragraphs) {
            if (para.length() > maxChars) {
                flush(buf, chunks);
                splitBySentence(para, chunks);
            } else if (buf.length() + para.length() + 2 > maxChars) {
                flush(buf, chunks);
                buf.append(para);
            } else {
                if (buf.length() > 0) {
                    buf.append("\n\n");
                }
                buf.append(para);
            }
        }
        flush(buf, chunks);

        // 3. 相邻块尾部 overlap,防止语义被切断
        return addOverlap(chunks);
    }

    /** 按句子切分;句子本身超长时按 maxChars 硬切 */
    private void splitBySentence(String text, List<String> out) {
        String[] sentences = text.split("(?<=[。!?;；.!?])");
        StringBuilder buf = new StringBuilder();
        for (String sentence : sentences) {
            String s = sentence.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.length() > maxChars) {
                flush(buf, out);
                for (int i = 0; i < s.length(); i += maxChars) {
                    out.add(s.substring(i, Math.min(i + maxChars, s.length())));
                }
            } else if (buf.length() + s.length() > maxChars) {
                flush(buf, out);
                buf.append(s);
            } else {
                buf.append(s);
            }
        }
        flush(buf, out);
    }

    private List<String> addOverlap(List<String> chunks) {
        if (chunks.size() <= 1 || overlapChars <= 0) {
            return chunks;
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String prev = i == 0 ? "" : chunks.get(i - 1);
            if (prev.length() > overlapChars) {
                prev = prev.substring(prev.length() - overlapChars);
            }
            result.add(prev.isEmpty() ? chunks.get(i) : prev + "\n" + chunks.get(i));
        }
        return result;
    }

    private void flush(StringBuilder buf, List<String> out) {
        if (buf.length() > 0) {
            out.add(buf.toString().trim());
            buf.setLength(0);
        }
    }
}
