package com.agentplatform.core.tool;

/**
 * 平台内工具的公共契约。
 *
 * 为什么工具参数必须是结构化类型（泛型 I）而不是裸字符串：
 * 1. 结构化类型能生成 JSON Schema，模型按 Schema 输出 JSON 对象参数，
 *    而非自由字符串——从源头约束模型输出格式；
 * 2. 实测 DashScope 兼容层对请求回放中的 function.arguments 有严格校验
 *    （要求 JSON 对象），字符串参数会在多轮工具调用时被 400 拒绝；
 * 3. 参数类型是"工具契约"的一部分：调用方、评测、文档都能从类型上看到契约。
 *
 * 演进路线：结构化参数（本次） -> 工具级权限/沙箱（M3）-> 工具版本化（远期）。
 *
 * @param <I> 参数类型：一个可被 Jackson 反序列化的 POJO/record
 */
public interface Tool<I> {

    /** 工具唯一名（模型靠它选择调用哪个工具，必须与描述一致且稳定） */
    String name();

    /** 给模型看的功能说明：做什么、参数是什么格式、何时该用 */
    String description();

    /** 参数类型：ReActLoop 用它生成 JSON Schema 并反序列化模型参数 */
    Class<I> inputType();

    /**
     * 执行工具。
     * 为什么不用受检异常：工具失败的统一处理策略是"把错误回填给模型当观察"
     * （ReAct 的 Observation 环节），由 Loop 层 catch RuntimeException 统一兜底，
     * 受检异常只会强迫每个调用点写无意义的 try/catch。
     */
    String execute(I arguments);
}
