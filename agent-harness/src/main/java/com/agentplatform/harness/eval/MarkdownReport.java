package com.agentplatform.harness.eval;

import java.util.List;

/**
 * Markdown 评测报告生成器。
 *
 * 为什么报告要落成文件而非只打印控制台：报告是评测的"证据链"，
 * prompt 改版后拿着两份报告对比，能直接定位"哪条用例从过变不过"；
 * 文件形式也方便 M4 接入 CI 后归档为构建产物。
 */
public final class MarkdownReport {

    private MarkdownReport() {
    }

    public static String render(String title, List<CaseResult> results) {
        long passed = results.stream().filter(CaseResult::passed).count();
        StringBuilder sb = new StringBuilder();
        sb.append("# 评测报告: ").append(title).append("\n\n");
        sb.append("- 总用例: ").append(results.size()).append("\n");
        sb.append("- 通过: ").append(passed).append("\n");
        sb.append("- 失败: ").append(results.size() - passed).append("\n");
        sb.append("- 通过率: ").append(results.isEmpty() ? "N/A" : String.format("%.0f%%", passed * 100.0 / results.size())).append("\n\n");

        for (CaseResult r : results) {
            sb.append("## ").append(r.passed() ? "PASS" : "FAIL").append(" - ")
                    .append(r.testCase().getName()).append(" (").append(r.testCase().getId()).append(")\n\n");
            sb.append("- 输入: `").append(r.testCase().getInput()).append("`\n");
            sb.append("- 耗时: ").append(r.durationMs()).append("ms，终态: ").append(r.run().getState()).append("\n");
            sb.append("- 最终回答: ").append(r.run().getFinalAnswer() == null ? "(无)" : r.run().getFinalAnswer()).append("\n\n");
            for (Assertion.AssertionResult a : r.assertionResults()) {
                sb.append("- ").append(a.passed() ? "[x]" : "[ ]").append(" ").append(a.assertion())
                        .append(" — ").append(a.detail()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
