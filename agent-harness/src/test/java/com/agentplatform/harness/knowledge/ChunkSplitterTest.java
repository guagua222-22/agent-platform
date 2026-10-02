package com.agentplatform.harness.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSplitterTest {

    private final ChunkSplitter splitter = new ChunkSplitter(100, 20);

    @Test
    void shortTextKeptWhole() {
        List<String> chunks = splitter.split("短文本");
        assertEquals(1, chunks.size());
        assertEquals("短文本", chunks.get(0));
    }

    @Test
    void longTextSplitAtParagraphBoundary() {
        String para1 = "A".repeat(60);
        String para2 = "B".repeat(60);
        List<String> chunks = splitter.split(para1 + "\n\n" + para2);

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).startsWith("AAA"), "第一块应为第一段");
        assertTrue(chunks.get(1).contains("BBB"), "第二块应包含第二段");
    }

    @Test
    void overlapPreventsBoundaryTruncation() {
        // 第一段 80 字符 + 第二段 80 字符，上限 100：应切为两块且第二块带第一块尾部重叠
        String para1 = "甲".repeat(80);
        String para2 = "乙".repeat(80);
        List<String> chunks = splitter.split(para1 + "\n\n" + para2);

        assertEquals(2, chunks.size());
        // 重叠 20：第二块开头应包含第一段的尾字符
        assertTrue(chunks.get(1).contains("甲"), "重叠窗口应把前块尾部并入后块");
    }

    @Test
    void hardCutWhenNoSeparator() {
        // 单段超长且无分隔符：最后手段硬切，但每块不超上限
        String longLine = "C".repeat(250);
        List<String> chunks = splitter.split(longLine);
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 100, "任何分块都不得超过上限: " + chunk.length());
        }
        assertTrue(chunks.size() >= 3);
    }

    @Test
    void blankTextYieldsNothing() {
        assertTrue(splitter.split("   \n  ").isEmpty());
    }

    @Test
    void invalidOverlapRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ChunkSplitter(100, 100));
    }
}
