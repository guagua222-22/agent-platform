package com.agentplatform.harness.knowledge;

import com.agentplatform.core.tool.Tool;
import com.agentplatform.harness.knowledge.KnowledgeService.SearchHit;

import java.util.List;

/**
 * 知识库检索工具：RAG Agent 的"手"。
 *
 * 为什么检索结果是"带来源的格式化文本"而不是原始 JSON：
 * 工具输出会直接进模型上下文，格式化的编号列表 + 来源标注
 * 既省 token 又引导模型在回答中引用来源（引用溯源是 RAG 的可信度命脉）。
 */
public class KnowledgeSearchTool implements Tool<KnowledgeSearchTool.SearchArgs> {

    private static final int DEFAULT_TOP_K = 4;

    private final KnowledgeService knowledge;

    public KnowledgeSearchTool(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    public record SearchArgs(String query) {
    }

    @Override
    public String name() {
        return "knowledge_search";
    }

    @Override
    public Class<SearchArgs> inputType() {
        return SearchArgs.class;
    }

    @Override
    public String description() {
        return "在知识库中检索与问题相关的文档片段。参数是 JSON 对象：{\"query\": \"检索关键词或问题\"}。" +
                "适用于回答需要背景知识、文档内容的问题。";
    }

    @Override
    public String execute(SearchArgs args) {
        if (args == null || args.query() == null || args.query().isBlank()) {
            throw new IllegalArgumentException("缺少 query 参数");
        }
        List<SearchHit> hits = knowledge.search(args.query().strip(), DEFAULT_TOP_K);
        if (hits.isEmpty()) {
            return "知识库中未检索到相关内容。";
        }
        StringBuilder sb = new StringBuilder("知识库检索结果：\n");
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            sb.append("[").append(i + 1).append("] 来源《").append(hit.docTitle())
                    .append("》(片段").append(hit.seq()).append("，相关度 ")
                    .append(String.format("%.3f", hit.score())).append(")\n")
                    .append(hit.content().strip()).append("\n");
        }
        sb.append("回答时请基于以上片段，并注明引用来源；若片段不足以回答，请明说知识库信息不足。");
        return sb.toString();
    }
}
