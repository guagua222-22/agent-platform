package com.agentplatform.app.api;

import com.agentplatform.harness.knowledge.KnowledgeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 知识库 API：上传文档、查看列表、向量检索。
 * RAG 全链路中"入库"与"检索"两个动作的可操作入口，也是演示 Milvus 的最小闭环。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledge;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public KnowledgeController(KnowledgeService knowledge, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.knowledge = knowledge;
        this.jdbc = jdbc;
    }

    public record UploadRequest(String title, String content) {
    }

    public record UploadResponse(String docId, int chunkCount, String status) {
    }

    public record DocInfo(String id, String title, int chunkCount, String status, Instant createdAt) {
    }

    @PostMapping("/docs")
    public UploadResponse upload(@RequestBody UploadRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()
                || request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("title 与 content 必填");
        }
        String docId = knowledge.upload(request.title().strip(), request.content());
        int chunks = jdbc.queryForObject(
                "SELECT chunk_count FROM knowledge_doc WHERE id = ?", Integer.class, docId);
        return new UploadResponse(docId, chunks, "READY");
    }

    @GetMapping("/docs")
    public List<DocInfo> docs() {
        return jdbc.query("""
                        SELECT id, title, chunk_count, status, created_at
                        FROM knowledge_doc ORDER BY created_at DESC LIMIT 50
                        """,
                (rs, n) -> new DocInfo(rs.getString("id"), rs.getString("title"),
                        rs.getInt("chunk_count"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    @GetMapping("/search")
    public List<KnowledgeService.SearchHit> search(@RequestParam String q,
                                                   @RequestParam(defaultValue = "5") int topK) {
        return knowledge.search(q, Math.max(1, Math.min(topK, 10)));
    }
}
