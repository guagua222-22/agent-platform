package com.agentplatform.core.model;

/**
 * Agent 一次运行的终态类型。
 *
 * 为什么用枚举而非布尔/字符串：运行状态是 Harness 评测与 Runtime 持久化的公共契约，
 * 枚举保证取值封闭，状态机迁移（{@link AgentRun#transitionTo}）可以显式校验非法路径。
 */
public enum RunState {
    /** 已创建，尚未开始执行 */
    CREATED,
    /** 执行中（Loop 策略正在驱动模型与工具） */
    RUNNING,
    /** 正常结束，已有最终回答 */
    COMPLETED,
    /** 异常结束（模型异常、工具异常、超步数等） */
    FAILED,
    /** 被外部取消（M1 做 Checkpoint/暂停恢复时启用该路径） */
    CANCELLED
}
