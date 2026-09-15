package com.agentplatform.core.util;

/**
 * 展示型字符串工具（供 Runtime/Harness 共用，避免两处各写一份截断逻辑）。
 */
public final class Strings {

    private static final int MAX_DETAIL_LENGTH = 200;

    private Strings() {
    }

    /** 事件详情截断：防止超长输入/回答把事件存档与 SSE 流撑爆 */
    public static String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_DETAIL_LENGTH ? s : s.substring(0, MAX_DETAIL_LENGTH) + "...";
    }
}
