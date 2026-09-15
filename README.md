# Agent Platform

AI Agent 平台（校招项目）：**Agent Harness（构建层）+ Agent Runtime（执行层）**，Java 21 + Spring Boot 3.5 + Spring AI（千问 DashScope，OpenAI 兼容协议）。

```
agent-platform/
├── agent-core       Harness 与 Runtime 的契约：Agent 定义、工具接口、事件、运行状态
├── agent-runtime    执行层：AgentRuntime 引擎（M1 起加持久化/断点/限流等基础设施）
├── agent-harness    构建层：Prompt 模板、工具注册、Loop 策略、评测体系
├── agent-app        Spring Boot 壳 + REST/SSE API + 示例 Agent
├── docs/            路线图与设计文档
└── python/          评测数据集与压测脚本（M3 起）
```

## 快速开始

```bash
# 1. 配置 API Key（千问平台 platform.qianwen.com 申请，环境变量，勿写入配置文件）
export AI_API_KEY=sk-xxxx

# 2. 单元测试（mock 模型，不花钱，默认跑）
./mvnw test

# 3. 集成测试（真调千问模型，需要网络与 KEY）
./mvnw test -DexcludedGroups=

# 4. 启动应用
./mvnw -pl agent-app -am spring-boot:run

# 5. SSE 流式对话
curl -N "http://localhost:8080/api/agents/calculator/chat?message=23*47等于多少"
```

详细规划见 [docs/roadmap.md](docs/roadmap.md)。
