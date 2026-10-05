package com.agentplatform.harness.subagent;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.core.tool.Tool;
import com.agentplatform.runtime.AgentRuntime;
import com.agentplatform.runtime.RunContext;
import com.agentplatform.core.model.AgentRun;
import com.agentplatform.core.model.RunState;

/**
 * SubAgent 委托工具：把一个完整的 Agent 包装成编排者可调用的工具（agent-as-tool 模式）。
 *
 * 多 Agent 协作的主流模式对比（面试高频）：
 * - agent-as-tool（本实现）：子 Agent 即工具，编排者用标准 ReAct 循环决定"何时委托谁"，
 *   路由逻辑由模型承担，新增专家 Agent 只需多包一个工具——扩展零编排代码；
 * - 中心化流程编排（如 LangGraph 状态图）：路由写死在图里，可控但不灵活；
 * - 自由对话（AutoGen 群聊）：Agent 互相对话，演示炫但生产不可控、token 翻倍。
 * 选 agent-as-tool 是因为它与平台已有契约零摩擦：委托就是一次工具调用，
 * 事件流、限流、评测断言（toolCalled("delegate_to_xxx")）全部免费获得。
 *
 * 嵌套运行的可观测性设计：子运行是独立完整的 AgentRun（独立事件存档/终态/持久化，
 * parent_run_id 关联父运行），同时子事件加前缀转发进父事件流——
 * 父看全局编排，子可独立审计，这是 M4 Tracing 树形视图的数据基础。
 */
public class SubAgentTool implements Tool<SubAgentTool.DelegateArgs> {

    public record DelegateArgs(String task) {
    }

    private final AgentDefinition subAgent;
    private final AgentRuntime runtime;

    public SubAgentTool(AgentDefinition subAgent, AgentRuntime runtime) {
        this.subAgent = subAgent;
        this.runtime = runtime;
    }

    @Override
    public String name() {
        return "delegate_to_" + subAgent.getName();
    }

    @Override
    public Class<DelegateArgs> inputType() {
        return DelegateArgs.class;
    }

    @Override
    public String description() {
        // 描述直接透出子 Agent 的能力边界：编排者（模型）靠这段文字决定路由，
        // 子 Agent 的 description 质量就是多 Agent 系统的路由质量
        return "把子任务委托给专家 Agent「" + subAgent.getName() + "」执行并返回其结果。"
                + "该专家的能力：" + subAgent.getDescription()
                + "。参数是 JSON 对象：{\"task\": \"完整、自包含的子任务描述（子 Agent 看不到你们的对话上下文）\"}";
    }

    @Override
    public String execute(DelegateArgs args) {
        if (args == null || args.task() == null || args.task().isBlank()) {
            throw new IllegalArgumentException("缺少 task 参数：子 Agent 看不到对话上下文，任务描述必须自包含");
        }
        RunContext context = RunContext.current();
        if (context == null) {
            // 委托只能在某次运行内发生——裸调工具没有父运行可挂靠，属于装配错误
            throw new IllegalStateException("SubAgent 委托只能在 Agent 运行内进行");
        }
        if (context.depth() + 1 > RunContext.MAX_DEPTH) {
            // 深度熔断：模型出现"委托套委托"的循环倾向时硬性截断，
            // 比靠提示词约束可靠——提示词是软约束，代码才是硬边界
            throw new IllegalStateException(
                    "嵌套深度超过上限 " + RunContext.MAX_DEPTH + "，疑似委托循环，已截断");
        }

        AgentRun subRun = runtime.runChild(subAgent, args.task().strip());
        if (subRun.getState() != RunState.COMPLETED) {
            // 子运行失败作为观察回填给编排者：由编排者决定换专家、换拆法还是向用户坦白，
            // 而不是直接把整个父运行拖死——局部失败不应放大为全局失败
            return "子 Agent「" + subAgent.getName() + "」执行失败：" + subRun.getError()
                    + "（子运行 id=" + subRun.getId() + "）";
        }
        return "子 Agent「" + subAgent.getName() + "」的结果：\n" + subRun.getFinalAnswer();
    }
}
