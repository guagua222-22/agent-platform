package com.agentplatform.harness.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归字符切分：按段落/句子/字符三级分隔符逐级切分，保证分块不超上限。
 *
 * 为什么切分是 RAG 质量的第一道关（面试高频）：
 * 1. 分块太小 → 语义不完整，检索命中"碎片"；太大 → 检索精度下降、上下文浪费；
 * 2. 重叠窗口解决"边界截断"：一句话恰好被切在两块中间时，重叠让两侧都能读到完整语义；
 * 3. 三级分隔符（段落→句子→硬切）比固定长度更尊重语义边界。
 * 更高级的语义切分（按 embedding 相似度找边界）留给 M3 演进。
 */
public class ChunkSplitter {

    private static final List<String> SEPARATORS = List.of("\n\n", "\n", "。", "！", "？", "；", ". ", "! ", "? ");

    private final int maxLength;
    private final int overlap;

    public ChunkSplitter(int maxLength, int overlap) {
        if (overlap >= maxLength) {
            throw new IllegalArgumentException("重叠长度必须小于分块上限: overlap=" + overlap + " max=" + maxLength);
        }
        this.maxLength = maxLength;
        this.overlap = overlap;
    }

    public List<String> split(String text) {
        List<String> pieces = new ArrayList<>();
        splitRecursive(text, 0, pieces);
        return pieces;
    }

    private void splitRecursive(String text, int depth, List<String> out) {
        if (text.isBlank()) {
            return;
        }
        String trimmed = text.strip();
        if (trimmed.length() <= maxLength) {
            out.add(trimmed);
            return;
        }
        if (depth < SEPARATORS.size()) {
            String sep = SEPARATORS.get(depth);
            int cut = findCut(trimmed, sep);
            if (cut > 0) {
                String left = trimmed.substring(0, cut);
                String right = trimmed.substring(cut + sep.length());
                splitRecursive(left, depth, out);
                // 重叠窗口：左块尾部并入右块，防边界截断。
                // 关键：右块必须比输入"短"，否则无限递归——
                // 右块 = 左块尾(≤overlap) + 原右半，长度必然严格小于输入
                String leftTail = left.substring(Math.max(0, left.length() - overlap));
                splitRecursive(leftTail + right, depth + 1, out);
                return;
            }
        }
        // 所有分隔符都不命中：硬切（最后手段，保证不超长）
        String head = trimmed.substring(0, maxLength);
        out.add(head);
        splitRecursive(trimmed.substring(maxLength - overlap), depth, out);
    }

    /** 找靠近中点的分隔符位置，让分块长度更均衡 */
    private int findCut(String text, String sep) {
        int mid = text.length() / 2;
        int pos = -1;
        int idx = text.indexOf(sep);
        while (idx >= 0 && idx < text.length()) {
            pos = idx;
            if (idx >= mid) {
                break;
            }
            idx = text.indexOf(sep, idx + sep.length());
        }
        return pos > 0 ? pos : -1;
    }
}
