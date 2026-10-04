package com.agentplatform.harness.loop;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.agent.AgentEventSink;
import com.agentplatform.core.agent.LoopStrategy;
import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.core.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute 循环：先规划后执行再汇总，三阶段分明。
 *
 * 与 ReAct 的取舍（面试高频对比题）：
 * - ReAct 是"边想边做"：每步都由模型现场决策，灵活但容易在长任务中漂移、绕路；
 * - Plan-and-Execute 是"先想再做"：计划一次性生成，执行阶段按图施工，
 *   长任务的 token 更省、路径可预期（计划本身可审计、可进事件流），
 *   代价是计划错了不回头（没有 Replan，M3 可在此基础上加"执行失败触发重规划"）。
 *
 * 为什么计划用"编号列表文本"而不是让模型输出 JSON：
 * 文本解析容错高（模型偶尔多一句前言也能兼容），且计划本就是给人看的——
 * PLAN_GENERATED 事件直接透出全文，前端与评测都能消费。
 */
public class PlanExecuteLoop implements LoopStrategy {

    private static final Logger log = LoggerFactory.getLogger(PlanExecuteLoop.class);

    /** 规划提示词：约束输出为纯编号列表，降低解析失败率 */
    private static final String PLAN_PROMPT =
            "你是规划器。把用户任务拆成有序的执行步骤，每步一行、以序号开头（1. 2. 3. ...），" +
            "步骤要具体可执行，最多 %d 步。只输出步骤列表，不要解释。";

    /** 汇总提示词：明确"基于执行记录回答"，防止模型抛开结果自由发挥 */
    private static final String SUMMARIZE_PROMPT =
            "你是汇总器。基于【原始任务】和【各步骤执行结果】给出最终回答。" +
            "严格以执行结果为依据，不得编造步骤中不存在的信息。";

    /** 匹配 "1. xxx" / "2、xxx" / "3) xxx" / "- xxx" 等常见列表行 */
    private static final Pattern STEP_LINE =
            Pattern.compile("^(?:\\d+\\s*[.、)）]|-)\\s*(.+)$");

    /** 单步骤的执行轮次上限：步骤粒度比整个任务小，预算也相应收紧 */
    private static final int STEP_ROUND_BUDGET = 3;

    private final ChatModel model;

    public PlanExecuteLoop(ChatModel model) {
        this.model = model;
    }

    @Override
    public String name() {
        return "plan-execute";
    }

    @Override
    public void execute(AgentDefinition definition, AgentRun run, AgentEventSink sink) {
        ToolCallback[] callbacks = ToolCallbacks.of(definition);
        Map<String, ToolCallback> callbacksByName = ToolCallbacks.byName(callbacks);
        ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .build();

        List<String> plan = generatePlan(definition, run, sink);
        List<String> stepResults = executePlan(definition, run, sink, plan, toolOptions, callbacksByName);
        summarize(definition, run, sink, stepResults);
    }

    // ---------- 阶段一：规划 ----------

    private List<String> generatePlan(AgentDefinition definition, AgentRun run, AgentEventSink sink) {
        sink.emit(AgentEvent.of(AgentEvent.EventType.LLM_CALLED, "phase=plan"));
        String toolCatalog = definition.getTools().stream()
                .map(t -> "- " + t.name() + ": " + t.description())
                .collect(Collectors.joining("\n"));
        ChatResponse response = model.call(new Prompt(List.of(
                new SystemMessage(definition.getSystemPrompt()
                        + "\n" + String.format(PLAN_PROMPT, definition.getMaxSteps())
                        + "\n可用工具：\n" + toolCatalog),
                new UserMessage(run.getInput()))));
        List<String> steps = parsePlan(response.getResult().getOutput().getText());
        if (steps.isEmpty()) {
            // 模型没按格式输出：整任务降级为单步执行，不让规划失败拖垮运行
            log.warn("计划解析为空，降级为单步执行 run={}", run.getId());
            steps = List.of(run.getInput());
        }
        if (steps.size() > definition.getMaxSteps()) {
            // maxSteps 语义 = 计划步数上限：超出截断而非报错，已规划部分仍值得执行
            sink.emit(AgentEvent.of(AgentEvent.EventType.STEP_COMPLETED,
                    "计划超出 maxSteps=" + definition.getMaxSteps() + "，截断为前 " + definition.getMaxSteps() + " 步"));
            steps = steps.subList(0, definition.getMaxSteps());
        }
        StringBuilder detail = new StringBuilder("steps=").append(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            detail.append('\n').append(i + 1).append(". ").append(steps.get(i));
        }
        sink.emit(AgentEvent.of(AgentEvent.EventType.PLAN_GENERATED, detail.toString()));
        return new ArrayList<>(steps);
    }

    static List<String> parsePlan(String text) {
        List<String> steps = new ArrayList<>();
        for (String line : text.split("\n")) {
            Matcher m = STEP_LINE.matcher(line.trim());
            if (m.matches()) {
                steps.add(m.group(1).trim());
            }
        }
        return steps;
    }

    // ---------- 阶段二：逐步执行 ----------

    private List<String> executePlan(AgentDefinition definition, AgentRun run, AgentEventSink sink,
                                     List<String> plan, ToolCallingChatOptions toolOptions,
                                     Map<String, ToolCallback> callbacksByName) {
        // 执行阶段共享一段消息历史：后续步骤能看到前面步骤的结果，任务才有"接力"效果
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(definition.getSystemPrompt()
                + "\n你是执行器。按计划逐步完成任务，每步可用工具；拿到工具结果后给出该步结论。"));
        history.add(new UserMessage("原始任务：" + run.getInput()));

        List<String> results = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            String step = plan.get(i);
            sink.emit(AgentEvent.of(AgentEvent.EventType.PLAN_STEP_STARTED,
                    "step=" + (i + 1) + "/" + plan.size() + " " + Strings.abbreviate(step)));
            history.add(new UserMessage("步骤 " + (i + 1) + "/" + plan.size() + "：" + step + "\n请完成该步骤。"));

            String outcome = runStep(definition, run, sink, history, toolOptions, callbacksByName, i + 1);
            results.add("步骤 " + (i + 1) + ": " + step + "\n结果: " + outcome);
            sink.emit(AgentEvent.of(AgentEvent.EventType.PLAN_STEP_COMPLETED,
                    "step=" + (i + 1) + " result=" + Strings.abbreviate(outcome)));
        }
        return results;
    }

    /** 单步执行：一个预算受限的迷你 ReAct 循环，产出该步结论文本 */
    private String runStep(AgentDefinition definition, AgentRun run, AgentEventSink sink,
                           List<Message> history, ToolCallingChatOptions toolOptions,
                           Map<String, ToolCallback> callbacksByName, int stepNo) {
        for (int round = 1; round <= STEP_ROUND_BUDGET; round++) {
            sink.emit(AgentEvent.of(AgentEvent.EventType.LLM_CALLED,
                    "phase=execute step=" + stepNo + " round=" + round));
            ChatResponse response = model.call(new Prompt(history, toolOptions));
            AssistantMessage assistant = response.getResult().getOutput();
            history.add(assistant);

            if (!assistant.hasToolCalls()) {
                return assistant.getText();
            }

            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                sink.emit(AgentEvent.of(AgentEvent.EventType.TOOL_CALLED,
                        "step=" + stepNo + " " + call.name() + "(" + Strings.abbreviate(call.arguments()) + ")"));
                long start = System.currentTimeMillis();
                String result;
                String error = null;
                try {
                    ToolCallback callback = callbacksByName.get(call.name());
                    if (callback == null) {
                        throw new IllegalArgumentException("模型调用了不存在的工具: " + call.name());
                    }
                    result = callback.call(call.arguments());
                } catch (Exception e) {
                    result = null;
                    error = e.getMessage();
                }
                sink.emit(AgentEvent.of(AgentEvent.EventType.TOOL_RESULT,
                        "step=" + stepNo + " tool=" + call.name() + " durationMs=" + (System.currentTimeMillis() - start)
                                + (error == null ? " result=" + Strings.abbreviate(result) : " error=" + error)));
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        call.id(), call.name(), error == null ? result : "工具执行失败: " + error));
            }
            history.add(ToolResponseMessage.builder().responses(toolResponses).build());
        }
        // 步骤内轮次耗尽：不强杀整个运行，记录现状交给汇总阶段——单步失败不应拖垮整体
        return "（该步未在 " + STEP_ROUND_BUDGET + " 轮内完成，已取得部分进展）";
    }

    // ---------- 阶段三：汇总 ----------

    private void summarize(AgentDefinition definition, AgentRun run, AgentEventSink sink, List<String> stepResults) {
        sink.emit(AgentEvent.of(AgentEvent.EventType.LLM_CALLED, "phase=summarize"));
        ChatResponse response = model.call(new Prompt(List.of(
                new SystemMessage(definition.getSystemPrompt() + "\n" + SUMMARIZE_PROMPT),
                new UserMessage("【原始任务】\n" + run.getInput()
                        + "\n\n【各步骤执行结果】\n" + String.join("\n\n", stepResults)
                        + "\n\n请给出最终回答。"))));
        run.complete(response.getResult().getOutput().getText());
    }
}
