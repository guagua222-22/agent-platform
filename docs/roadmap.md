# Agent 平台路线图（v3）

> 目标岗位：AI Agent 开发工程师（2026 秋招）。本项目 = 简历核心项目。

## 一、项目定位

**自己写的 Harness（构建层）驱动自己写的 Runtime（执行层），在其上运行三类生产 Agent。**

- **Agent Harness（构建层）**：定义与组装 Agent —— Prompt、Tool、Skill、Memory、Agent Loop、SubAgent，以及验证构建质量的评测体系。
- **Agent Runtime（执行层）**：生产运行基础设施 —— 执行引擎、持久化、Checkpoint、任务队列、并发、重试、权限、沙箱、HITL、Tracing、扩缩容。

```
┌──────────── Agent Harness（构建层）────────────────┐
│  Prompt 管理 · Tool 注册 · Skill 库 · Memory 配置  │
│  Agent Loop 策略（ReAct / Plan-Execute）          │
│  SubAgent 编排 · 评测体系（数据集/断言/judge/报告） │
└──────────────────────┬─────────────────────────────┘
                       │ 组装出 Agent 定义
┌──────────── Agent Runtime（执行层）────────────────┐
│  执行引擎 · 持久化 · Checkpoint · 任务队列 · 并发   │
│  重试降级 · 权限 · 沙箱 · HITL · Tracing · 限流    │
└──────────────────────┬─────────────────────────────┘
                       │ 之上运行
     业务 Agent：知识库 RAG · 通用任务 · 多 Agent 协作
```

## 二、概念覆盖映射（每个词都有落点）

| 概念 | 落点模块 | 里程碑 |
|---|---|---|
| Prompt | harness：模板+版本管理 | M0 |
| Tool | harness：注册中心 | M0 |
| Skill | harness：能力包（工具+提示词组合） | M2 |
| Memory | harness 配置 + runtime 存储 | M1 短期 / M2 长期摘要 |
| Agent Loop | harness：LoopStrategy 策略 | M0 ReAct / M2 Plan-Execute |
| SubAgent | harness：编排器 | M3 |
| 持久化 | runtime：MySQL 会话/消息 | M1 |
| Checkpoint | runtime：Redis 断点续跑 | M1 |
| 任务队列 | runtime：RocketMQ | M3 |
| 并发 | runtime：虚拟线程 | M1 |
| 重试 | runtime：Resilience4j 模型网关 | M1 |
| 权限 | runtime：工具 ACL + API Key | M3 |
| 沙箱 | runtime：工具执行隔离+超时 | M3 |
| HITL | runtime：人工审批节点 | M3 |
| Tracing | runtime：全链路+Token 成本 | M4 |
| 扩缩容 | runtime：无状态化设计 | M4 |
| 评测 | harness：数据集+断言+LLM-as-judge | M0 起步，持续深化 |

## 三、里程碑

| 里程碑 | 时间 | 内容 | 面试考点 |
|---|---|---|---|
| M0 闭环骨架 | 第 1 周 | 工程+契约抽象+Harness 最小版+Runtime 最小版+评测最小版+SSE API | Harness/Runtime 边界、SSE、Agent 状态机 |
| M1 可靠性底座 | 第 2 周 | MySQL 持久化、Redis Checkpoint、重试降级、虚拟线程、限流 | 断点续跑、幂等、限流算法、虚拟线程 |
| M2 能力扩充 | 第 3~4 周 | Skill 库、Memory 分层、Plan-Execute、RAG Agent、通用任务 Agent | RAG 全链路、混合检索、上下文工程 |
| M3 多 Agent+安全 | 第 5 周 | SubAgent 编排、RocketMQ 队列、沙箱、ACL、HITL | 多 Agent 模式、MQ 选型、沙箱方案 |
| M4 生产收尾 | 第 6 周起 | Tracing+成本大盘、无状态化、Docker Compose、Python 压测/评测、MCP | 可观测、成本治理、评测指标、MCP |

## 四、技术选型

- Java 21 + Spring Boot 3.5.16 + Spring AI 1.1.8（DeepSeek starter）
- 模型：DeepSeek deepseek-chat；Embedding：BGE-M3（硅基流动 API，DeepSeek 无 embedding 接口）
- 存储：MySQL 8（容器映射 3307，避开本机原生 3306）、Redis 7、Milvus（M2）
- 中间件：RocketMQ 5（M3，复用秒杀项目经验）
- 可观测：Micrometer + Prometheus + Grafana（M4）
- Python 辅助（`python/` 目录）：评测数据集构建、压测、judge 实验（HTTP 调 Java API）

## 五、面试考点优先级

- 主线（Agent 工程）：Agent 架构模式、上下文工程、评测体系、ReAct/工具调用、多 Agent 编排、RAG、Token 成本
- 基础线（语言关）：Java 八股、JVM、并发、MySQL/Redis（笔试与一面必过线，项目推进中穿插讲）
- 加分线：MCP、可观测、压测
