package com.agentplatform.harness.knowledge;

/**
 * Markdown/纯文本解析：去除代码块围栏与图片链接语法，保留标题层级与正文。
 * 为什么去 markdown 语法：向量检索匹配的是语义文本，
 * "![图](url)" 这类标记对 embedding 是噪声，精简后分块更干净。
 */
public class MarkdownDocumentParser implements DocumentParser {

    @Override
    public String sourceType() {
        return "markdown";
    }

    @Override
    public String parse(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String line : raw.split("\n")) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            // 代码块围栏不承载自然语言语义，跳过
            if (t.startsWith("```")) {
                continue;
            }
            // 图片/链接语法：只留链接文字
            t = t.replaceAll("!\\[[^]]*]\\([^)]*\\)", "");
            t = t.replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1");
            // 表格分隔行（|---|）无语义，跳过
            if (t.matches("^\\|?[\\s:|-]+\\|?$")) {
                continue;
            }
            out.append(t).append('\n');
        }
        return out.toString();
    }
}
