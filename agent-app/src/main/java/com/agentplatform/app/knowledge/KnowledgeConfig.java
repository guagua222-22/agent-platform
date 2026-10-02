package com.agentplatform.app.knowledge;

import com.agentplatform.harness.knowledge.KnowledgeService;
import com.agentplatform.harness.knowledge.KnowledgeSearchTool;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 知识库装配：Milvus 客户端（gRPC 连接）、知识服务、检索工具。
 */
@Configuration
public class KnowledgeConfig {

    @Bean(destroyMethod = "close")
    public MilvusServiceClient milvusClient() {
        // 连接本地 standalone（docker-compose 的 agent-milvus，gRPC 19530）
        return new MilvusServiceClient(ConnectParam.newBuilder()
                .withUri("http://localhost:19530")
                .build());
    }

    @Bean
    public KnowledgeService knowledgeService(MilvusServiceClient milvus, EmbeddingModel embeddingModel,
                                             JdbcTemplate jdbcTemplate) {
        // EmbeddingModel 由 spring-ai OpenAI 适配器提供（DashScope text-embedding-v3）
        return new KnowledgeService(milvus, embeddingModel, jdbcTemplate);
    }

    @Bean
    public KnowledgeSearchTool knowledgeSearchTool(KnowledgeService knowledgeService) {
        return new KnowledgeSearchTool(knowledgeService);
    }
}
