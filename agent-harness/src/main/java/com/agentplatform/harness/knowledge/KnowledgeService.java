package com.agentplatform.harness.knowledge;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库服务：文档解析 -> 切分 -> 向量化 -> Milvus 入库 -> 向量检索。
 *
 * 存储分工（面试高频考点"RAG 的存储架构"）：
 * MySQL 存文档/分块元数据（可事务、可回查），Milvus 存向量（HNSW 索引专长）。
 * 两侧用同一个主键对齐：先写 MySQL 拿 chunk 自增 id，再以该 id 为主键写 Milvus——
 * 检索命中后字段值自带来源，无需回查（少一次数据库往返）。
 */
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    public static final String COLLECTION = "knowledge_chunks";
    private static final int EMBEDDING_DIM = 1024; // text-embedding-v3 默认维度
    private static final int EMBED_BATCH_SIZE = 10; // DashScope embedding 单请求上限

    private final MilvusServiceClient milvus;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbc;
    private final ChunkSplitter splitter = new ChunkSplitter(500, 50);
    private final DocumentParser parser = new MarkdownDocumentParser();

    private volatile boolean collectionReady = false;

    public KnowledgeService(MilvusServiceClient milvus, EmbeddingModel embeddingModel, JdbcTemplate jdbc) {
        this.milvus = milvus;
        this.embeddingModel = embeddingModel;
        this.jdbc = jdbc;
    }

    /** 上传文档：入库成功才算 READY，失败保持 FAILED 便于重试 */
    public String upload(String title, String content) {
        String docId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO knowledge_doc (id, title, status) VALUES (?, ?, 'PROCESSING')", docId, title);
        try {
            String text = parser.parse(content);
            List<String> chunks = splitter.split(text);
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("文档解析后无有效内容");
            }
            ensureCollection();

            // 第一步：分块先写 MySQL，拿自增主键（该主键将作为 Milvus 向量主键）
            List<Long> chunkIds = insertChunksToMysql(docId, chunks);
            // 第二步：分批向量化 + 写 Milvus（主键=MySQL chunk id，两侧对齐）
            insertVectors(docId, title, chunkIds, chunks);

            jdbc.update("UPDATE knowledge_doc SET chunk_count = ?, status = 'READY' WHERE id = ?",
                    chunks.size(), docId);
            log.info("知识入库完成 doc={} chunks={}", docId, chunks.size());
            return docId;
        } catch (Exception e) {
            jdbc.update("UPDATE knowledge_doc SET status = 'FAILED' WHERE id = ?", docId);
            log.error("知识入库失败 doc={}", docId, e);
            throw e;
        }
    }

    /** 向量检索：topK 命中，字段值自带来源（doc 标题/序号/文本） */
    public List<SearchHit> search(String query, int topK) {
        ensureCollection();
        List<Float> queryVec = embed(List.of(query)).get(0);
        R<SearchResults> resp = milvus.search(SearchParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withMetricType(MetricType.COSINE)
                .withOutFields(List.of("doc_id", "title", "seq", "content"))
                .withTopK(topK)
                .withFloatVectors(List.of(queryVec))
                .withParams("{\"nprobe\":10}")
                .build());
        if (resp.getStatus() != 0) {
            throw new IllegalStateException("向量检索失败: " + resp.getMessage());
        }

        List<SearchHit> hits = new ArrayList<>();
        SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
        List<SearchResultsWrapper.IDScore> scores;
        try {
            scores = wrapper.getIDScore(0);
        } catch (Exception e) {
            throw new IllegalStateException("检索结果解析失败", e);
        }
        for (SearchResultsWrapper.IDScore s : scores) {
            Map<String, Object> fv = s.getFieldValues();
            hits.add(new SearchHit(s.getLongID(),
                    String.valueOf(fv.get("title")),
                    ((Number) fv.get("seq")).intValue(),
                    String.valueOf(fv.get("content")),
                    s.getScore()));
        }
        return hits;
    }

    private List<Long> insertChunksToMysql(String docId, List<String> chunks) {
        List<Long> ids = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            final int seq = i + 1;
            final int idx = i; // lambda 只能捕获 effectively final 变量
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO knowledge_chunk (doc_id, seq, content) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, docId);
                ps.setInt(2, seq);
                ps.setString(3, chunks.get(idx));
                return ps;
            }, keyHolder);
            // 用 KeyHolder 而非 LAST_INSERT_ID()：连接池下两次查询可能落到不同连接
            ids.add(keyHolder.getKey().longValue());
        }
        return ids;
    }

    private void insertVectors(String docId, String title, List<Long> chunkIds, List<String> chunks) {
        for (int batch = 0; batch * EMBED_BATCH_SIZE < chunks.size(); batch++) {
            int from = batch * EMBED_BATCH_SIZE;
            int to = Math.min(from + EMBED_BATCH_SIZE, chunks.size());
            List<String> part = chunks.subList(from, to);
            List<Long> partIds = chunkIds.subList(from, to);
            List<List<Float>> vectors = embed(part);
            // seq 是文档内序号（from+1 .. to），与 chunk 主键不是一回事
            List<Long> seqs = java.util.stream.LongStream.range(from + 1, to + 1).boxed().toList();

            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("id", partIds));
            fields.add(new InsertParam.Field("doc_id", part.stream().map(p -> docId).toList()));
            fields.add(new InsertParam.Field("title", part.stream().map(p -> title).toList()));
            fields.add(new InsertParam.Field("seq", seqs));
            fields.add(new InsertParam.Field("content", part));
            fields.add(new InsertParam.Field("embedding", vectors));

            R<MutationResult> resp = milvus.insert(InsertParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .withFields(fields)
                    .build());
            if (resp.getStatus() != 0) {
                throw new IllegalStateException("向量入库失败: " + resp.getMessage());
            }
            if (resp.getData().getInsertCnt() != part.size()) {
                throw new IllegalStateException("向量入库数量不符: 期望 " + part.size()
                        + " 实际 " + resp.getData().getInsertCnt());
            }
        }
    }

    /** 批量向量化（单请求多文本，省调用次数与成本） */
    private List<List<Float>> embed(List<String> texts) {
        EmbeddingResponse resp = embeddingModel.embedForResponse(texts);
        List<List<Float>> vectors = new ArrayList<>();
        for (var result : resp.getResults()) {
            float[] output = result.getOutput();
            List<Float> vec = new ArrayList<>(output.length);
            for (float v : output) {
                vec.add(v);
            }
            vectors.add(vec);
        }
        return vectors;
    }

    /**
     * 集合懒加载：首次访问时建集合 + HNSW 索引 + 加载到内存。
     * 为什么 HNSW + COSINE：HNSW 是图索引，检索速度快（近似最近邻的工程标准解）；
     * COSINE 度量对文本 embedding 最稳（向量方向比长度更有语义）。
     */
    private synchronized void ensureCollection() {
        if (collectionReady) {
            return;
        }
        R<Boolean> has = milvus.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(COLLECTION).build());
        if (has.getData() == null || !has.getData()) {
            R<RpcStatus> created = milvus.createCollection(CreateCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .withFieldTypes(List.of(
                            FieldType.newBuilder().withName("id").withDataType(DataType.Int64)
                                    .withPrimaryKey(true).withAutoID(false).build(),
                            FieldType.newBuilder().withName("embedding").withDataType(DataType.FloatVector)
                                    .withDimension(EMBEDDING_DIM).build(),
                            FieldType.newBuilder().withName("doc_id").withDataType(DataType.VarChar)
                                    .withMaxLength(64).build(),
                            FieldType.newBuilder().withName("title").withDataType(DataType.VarChar)
                                    .withMaxLength(255).build(),
                            FieldType.newBuilder().withName("seq").withDataType(DataType.Int64).build(),
                            FieldType.newBuilder().withName("content").withDataType(DataType.VarChar)
                                    .withMaxLength(4096).build()
                    ))
                    .build());
            if (created.getStatus() != 0) {
                throw new IllegalStateException("建集合失败: " + created.getMessage());
            }
            R<RpcStatus> indexed = milvus.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .withFieldName("embedding")
                    .withIndexType(IndexType.HNSW)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam("{\"M\":16,\"efConstruction\":200}")
                    .build());
            if (indexed.getStatus() != 0) {
                throw new IllegalStateException("建索引失败: " + indexed.getMessage());
            }
        }
        R<RpcStatus> loaded = milvus.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(COLLECTION).build());
        if (loaded.getStatus() != 0) {
            throw new IllegalStateException("加载集合失败: " + loaded.getMessage());
        }
        collectionReady = true;
        log.info("Milvus 集合就绪: {}", COLLECTION);
    }

    public record SearchHit(long chunkId, String docTitle, int seq, String content, float score) {
    }
}
