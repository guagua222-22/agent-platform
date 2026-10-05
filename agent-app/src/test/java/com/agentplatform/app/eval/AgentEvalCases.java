package com.agentplatform.app.eval;

import com.agentplatform.core.model.AgentEvent;
import com.agentplatform.harness.eval.TestCase;

import java.util.List;

import static com.agentplatform.harness.eval.TestCase.answerContains;
import static com.agentplatform.harness.eval.TestCase.eventEmitted;
import static com.agentplatform.harness.eval.TestCase.runCompleted;
import static com.agentplatform.harness.eval.TestCase.toolCalled;
import static com.agentplatform.harness.eval.TestCase.toolNotCalled;

/**
 * Agent 评测用例集：与 AgentConfig 装配的 5 个 Agent 一一对应的质量基线。
 *
 * 为什么用例集独立成类而非散落在各测试里：
 * 1. 同一批用例要服务两种运行方式——mock 日常回归（本类剧本）与真模型发布前验证
 *    （{@code realModelCases}，去掉剧本只留断言），用例只有一份才不会两处分叉；
 * 2. 用例是 prompt 防漂移的锚点：改 AgentConfig 里的提示词后跑一遍，
 *    通过率掉了就是漂移了——这是"提示词版本化"之外的第二道保险。
 *
 * 用例设计原则（面试可讲）：
 * - 每个用例至少断言"过程"与"结果"两个维度：过程看事件流（工具路由对不对），
 *   结果看最终回答（答案内容对不对）——只断言结果会让"蒙对的幻觉"漏网；
 * - 反向断言同样重要：闲聊用例断言 toolNotCalled，防的是模型"勤劳过度"白烧 token。
 */
public final class AgentEvalCases {

    private AgentEvalCases() {
    }

    // ---------- 通用任务 Agent（assistant：数学 + 时间双技能路由） ----------

    public static List<EvalScenario> assistantScenarios() {
        return List.of(
                new EvalScenario("assistant",
                        TestCase.builder()
                                .id("ast-001").name("计算意图路由到 calculator")
                                .description("纯算术题必须走工具，不允许心算")
                                .input("57*83 等于多少")
                                .assertion(runCompleted())
                                .assertion(toolCalled("calculator"))
                                .assertion(answerContains("4731"))
                                .build(),
                        m -> m.scriptToolCall("calculator", "{\"expression\":\"57*83\"}")
                                .scriptAnswer("57*83 的计算结果是 4731。")),
                new EvalScenario("assistant",
                        TestCase.builder()
                                .id("ast-002").name("时间意图路由到 get_current_time")
                                .input("现在几点了")
                                .assertion(runCompleted())
                                .assertion(toolCalled("get_current_time"))
                                .assertion(toolNotCalled("calculator"))
                                .build(),
                        m -> m.scriptToolCall("get_current_time", "{}")
                                .scriptAnswer("现在是 2026-10-05 14:30:00。")),
                new EvalScenario("assistant",
                        TestCase.builder()
                                .id("ast-003").name("闲聊不触发任何工具")
                                .description("反向断言：工具调用是成本，不该调的时候一次都不能多")
                                .input("你好，介绍一下你自己")
                                .assertion(runCompleted())
                                .assertion(toolNotCalled("calculator"))
                                .assertion(toolNotCalled("get_current_time"))
                                .build(),
                        m -> m.scriptAnswer("你好！我是通用任务助手，会算数也会查时间。")),
                new EvalScenario("assistant",
                        TestCase.builder()
                                .id("ast-004").name("除法计算并复述算式")
                                .input("帮我算一下 100 除以 4")
                                .assertion(runCompleted())
                                .assertion(toolCalled("calculator"))
                                .assertion(answerContains("25"))
                                .build(),
                        m -> m.scriptToolCall("calculator", "{\"expression\":\"100/4\"}")
                                .scriptAnswer("100/4=25。")));
    }

    // ---------- RAG 知识库 Agent（knowledge：先检索后回答，必须引用来源） ----------

    /**
     * mock 版 RAG 用例：检索工具是桩（返回预置片段），
     * 验证的是"提示词是否约束模型先检索、回答是否带引用格式"这条链路。
     */
    public static List<EvalScenario> knowledgeScenarios() {
        return List.of(
                new EvalScenario("knowledge",
                        TestCase.builder()
                                .id("rag-001").name("命中问题先检索再回答并引用来源")
                                .description("RAG 可信度命脉：答案必须可溯源到文档片段")
                                .input("星舟公司的报销流程是什么")
                                .assertion(runCompleted())
                                .assertion(toolCalled("knowledge_search"))
                                .assertion(answerContains("来源"))
                                .build(),
                        m -> m.scriptToolCall("knowledge_search", "{\"query\":\"报销流程\"}")
                                .scriptAnswer("根据检索结果，报销需先填单再审批【来源：《星舟公司内部手册》片段2】。")),
                new EvalScenario("knowledge",
                        TestCase.builder()
                                .id("rag-002").name("知识库外问题明确拒答")
                                .description("检索不足时必须明说，编造答案是 RAG 最严重的事故")
                                .input("火星上最大的火山叫什么")
                                .assertion(runCompleted())
                                .assertion(toolCalled("knowledge_search"))
                                .assertion(answerContains("不足"))
                                .build(),
                        m -> m.scriptToolCall("knowledge_search", "{\"query\":\"火星最大的火山\"}")
                                .scriptAnswer("知识库信息不足，无法回答该问题。")));
    }

    // ---------- 规划型 Agent（planner：Plan-and-Execute 三阶段） ----------

    public static List<EvalScenario> plannerScenarios() {
        return List.of(
                new EvalScenario("planner",
                        TestCase.builder()
                                .id("plan-001").name("多步任务走完整三阶段")
                                .description("PLAN_GENERATED 证明走了规划；步骤事件证明按计划执行；汇总含计算结果")
                                .input("先算 12*12，再告诉我现在时间")
                                .assertion(runCompleted())
                                .assertion(eventEmitted(AgentEvent.EventType.PLAN_GENERATED))
                                .assertion(eventEmitted(AgentEvent.EventType.PLAN_STEP_COMPLETED))
                                .assertion(toolCalled("calculator"))
                                .assertion(answerContains("144"))
                                .build(),
                        m -> m.scriptAnswer("1. 计算 12*12\n2. 查询当前时间")
                                .scriptToolCall("calculator", "{\"expression\":\"12*12\"}")
                                .scriptAnswer("步骤1完成：12*12=144")
                                .scriptToolCall("get_current_time", "{}")
                                .scriptAnswer("步骤2完成：现在是下午")
                                .scriptAnswer("12*12 等于 144，当前时间是下午。")));
    }

    // ---------- 真模型集成测试用：同一批用例去掉剧本 ----------

    /**
     * 真模型只留"过程 + 结果"断言，剧本由真实模型自己演。
     * 前置条件：docker 基础设施在跑（MySQL/Redis/Milvus）、AI_API_KEY 已配、
     * 知识库已上传过文档（rag 用例依赖 Milvus 里有向量）。
     */
    public static List<EvalScenario> realModelScenarios() {
        // 剧本传 null：RealModelEvalTest 走 Spring 装配的真模型，不消费 script
        return List.of(
                new EvalScenario("assistant", assistantScenarios().get(0).testCase(), null),
                new EvalScenario("assistant", assistantScenarios().get(1).testCase(), null),
                new EvalScenario("assistant", assistantScenarios().get(2).testCase(), null),
                new EvalScenario("knowledge", knowledgeScenarios().get(0).testCase(), null),
                new EvalScenario("planner", plannerScenarios().get(0).testCase(), null));
    }
}
