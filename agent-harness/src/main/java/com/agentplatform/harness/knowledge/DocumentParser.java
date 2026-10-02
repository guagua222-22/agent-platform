package com.agentplatform.harness.knowledge;

/**
 * 文档解析：把原始文本/文件内容规整为可切分的纯文本。
 * M2 支持 TEXT/MARKDOWN（直接按 UTF-8 文本处理），
 * PDF/Word 解析（需 Apache PDFBox/POI）是 M3 的扩展点——解析器接口化就是为了插拔格式。
 */
public interface DocumentParser {

    /** 支持的格式标识（text/markdown/...） */
    String sourceType();

    /**
     * 解析为纯文本。
     * @param raw 原始内容（文本内容或文件字节按 UTF-8 解码后的字符串）
     */
    String parse(String raw);
}
