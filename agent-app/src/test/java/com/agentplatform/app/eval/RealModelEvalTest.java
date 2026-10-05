package com.agentplatform.app.eval;

import com.agentplatform.core.agent.AgentDefinition;
import com.agentplatform.harness.eval.CaseResult;
import com.agentplatform.harness.eval.HarnessRunner;
import com.agentplatform.harness.eval.MarkdownReport;
import com.agentplatform.runtime.AgentRuntime;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真模型集成评测：Spring 全量装配（千问 DashScope + MySQL + Redis + Milvus），
 * 用与 mock 相同的用例集验证模型本身的质量。
 *
 * 为什么必须起完整 Spring 上下文：RAG 用例依赖 Milvus 里的真实向量、
 * Embedding 模型与知识库服务——半截装配测的不是生产路径。
 *
 * 跑法（默认 mvn test 不执行，避免日常回归烧 token）：
 *   ./mvnw test -Pintegration -Dtest=RealModelEvalTest
 * 前置条件：docker-compose 基础设施在跑、AI_API_KEY 环境变量已配、
 * 知识库至少上传过一篇文档（rag-001 依赖 Milvus 中有向量）。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RealModelEvalTest {

    @Autowired
    private Map<String, AgentDefinition> agents;

    @Autowired
    private AgentRuntime runtime;

    @Test
    void realModelSuiteAllPass() throws IOException {
        List<CaseResult> allResults = new ArrayList<>();
        HarnessRunner runner = new HarnessRunner(runtime);
        for (EvalScenario scenario : AgentEvalCases.realModelScenarios()) {
            AgentDefinition agent = agents.get(scenario.agentName());
            allResults.addAll(runner.run(agent, List.of(scenario.testCase())));
        }

        // 报告带时间戳落盘：每次真模型验证留一份证据，prompt 改版前后可对比
        Path reportPath = Path.of("target", "eval", "real-model-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, MarkdownReport.render("真模型集成评测（qwen-plus）", allResults));
        System.out.println("评测报告: " + reportPath.toAbsolutePath());

        long passed = allResults.stream().filter(CaseResult::passed).count();
        assertEquals(allResults.size(), passed,
                "真模型评测未全过，详情见报告: " + reportPath.toAbsolutePath());
    }
}
