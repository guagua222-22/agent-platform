package com.agentplatform.harness.eval;

import com.agentplatform.core.model.AgentRun;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条评测用例：输入 + 一组断言。
 *
 * 为什么用例与 Agent 定义分离：同一 Agent 会被多个用例从不同角度验证
 * （正常计算、除零、超长表达式…），同一批用例也能回归不同版本的 Agent
 * （M1 起的 prompt 防漂移回归就是"用例不变、Agent 变"）。
 */
public class TestCase {

    private final String id;
    private final String name;
    private final String description;
    private final String input;
    private final List<Assertion> assertions;

    private TestCase(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.description = b.description;
        this.input = b.input;
        this.assertions = List.copyOf(b.assertions);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getInput() { return input; }
    public List<Assertion> getAssertions() { return assertions; }

    public static class Builder {
        private String id;
        private String name;
        private String description = "";
        private String input;
        private final List<Assertion> assertions = new ArrayList<>();

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder input(String input) { this.input = input; return this; }
        public Builder assertion(Assertion assertion) { this.assertions.add(assertion); return this; }

        public TestCase build() {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("用例必须有 id: 它是回归报告中的定位锚点");
            }
            if (input == null) {
                throw new IllegalArgumentException("用例必须有输入: " + id);
            }
            if (assertions.isEmpty()) {
                throw new IllegalArgumentException("用例至少一条断言: " + id + "，无断言的用例没有判定意义");
            }
            return new TestCase(this);
        }
    }

    /** 便捷方法：断言运行是否正常完成（COMPLETED） */
    public static Assertion runCompleted() {
        return new Assertion() {
            @Override
            public String describe() {
                return "运行正常完成";
            }

            @Override
            public AssertionResult evaluate(AgentRun run) {
                boolean ok = run.getState() == com.agentplatform.core.model.RunState.COMPLETED;
                return new AssertionResult(describe(), ok,
                        ok ? "状态=COMPLETED" : "状态=" + run.getState() + " error=" + run.getError());
            }
        };
    }

    /** 便捷方法：断言最终回答包含某字符串 */
    public static Assertion answerContains(String expected) {
        return new Assertion() {
            @Override
            public String describe() {
                return "最终回答包含 \"" + expected + "\"";
            }

            @Override
            public AssertionResult evaluate(AgentRun run) {
                String answer = run.getFinalAnswer();
                boolean ok = answer != null && answer.contains(expected);
                return new AssertionResult(describe(), ok,
                        ok ? "回答=" + answer : "回答=" + answer);
            }
        };
    }

    /** 便捷方法：断言某工具在运行过程中被调用过 */
    public static Assertion toolCalled(String toolName) {
        return new Assertion() {
            @Override
            public String describe() {
                return "工具 " + toolName + " 被调用";
            }

            @Override
            public AssertionResult evaluate(AgentRun run) {
                boolean ok = run.getEvents().stream().anyMatch(
                        e -> e.type() == com.agentplatform.core.model.AgentEvent.EventType.TOOL_CALLED
                                && e.detail().startsWith(toolName + "("));
                return new AssertionResult(describe(), ok, ok ? "事件流中存在 TOOL_CALLED" : "事件流中未找到该工具调用");
            }
        };
    }
}
