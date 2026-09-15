package com.agentplatform.core.tool;

/**
 * 平台内工具的公共契约。
 *
 * 为什么 M0 的参数是单个字符串而非结构化 Schema：
 * M0 只跑通闭环（计算器工具用表达式字符串足够），结构化参数 Schema
 * 涉及 JSON Schema 生成与校验，是 M1"工具契约设计"的专题内容。
 * 演进路线：String 参数 -> 带 JSON Schema 的结构化参数（M1）-> 工具级权限/沙箱（M3）。
 */
public interface Tool {

    /** 工具唯一名（模型靠它选择调用哪个工具，必须与描述一致且稳定） */
    String name();

    /** 给模型看的功能说明：做什么、参数是什么格式、何时该用 */
    String description();

    /**
     * 执行工具。
     * 为什么不用受检异常：工具失败的统一处理策略是"把错误回填给模型当观察"
     * （ReAct 的 Observation 环节），由 Loop 层 catch RuntimeException 统一兜底，
     * 受检异常只会强迫每个调用点写无意义的 try/catch。
     */
    String execute(String arguments);
}
