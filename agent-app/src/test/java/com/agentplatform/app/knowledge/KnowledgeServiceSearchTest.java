package com.agentplatform.app.knowledge;

import com.agentplatform.harness.knowledge.KnowledgeService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.FieldData;
import io.milvus.grpc.IDs;
import io.milvus.grpc.LongArray;
import io.milvus.grpc.ScalarField;
import io.milvus.grpc.SearchResultData;
import io.milvus.grpc.SearchResults;
import io.milvus.grpc.StringArray;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.SearchParam;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeServiceSearchTest {

    @Test
    void searchBuildsValidSdkRequestAndReturnsSourceEvidence() {
        MilvusServiceClient milvus = mock(MilvusServiceClient.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        String query = "会议室设备没声音找谁？";
        String evidence = "极光会议室的设备管理员是周小满，内部短号为 7312。";
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        when(embeddingModel.embedForResponse(List.of(query)))
                .thenReturn(new EmbeddingResponse(List.of(new Embedding(vector, 0))));
        when(milvus.hasCollection(any(HasCollectionParam.class))).thenReturn(R.success(true));
        when(milvus.loadCollection(any(LoadCollectionParam.class))).thenReturn(R.success());
        SearchResultData result = SearchResultData.newBuilder()
                .setPrimaryFieldName("id")
                .setNumQueries(1).setTopK(1).addTopks(1)
                .setIds(IDs.newBuilder().setIntId(LongArray.newBuilder().addData(42)))
                .addScores(0.91f)
                .addFieldsData(stringField("title", "星舟公司内部手册"))
                .addFieldsData(FieldData.newBuilder().setFieldName("seq").setType(DataType.Int64)
                        .setScalars(ScalarField.newBuilder().setLongData(LongArray.newBuilder().addData(1))))
                .addFieldsData(stringField("content", evidence))
                .build();
        when(milvus.search(any(SearchParam.class)))
                .thenReturn(R.success(SearchResults.newBuilder().setResults(result).build()));
        KnowledgeService service = new KnowledgeService(milvus, embeddingModel, mock(JdbcTemplate.class));

        List<KnowledgeService.SearchHit> hits = service.search(query, 5);

        ArgumentCaptor<SearchParam> request = ArgumentCaptor.forClass(SearchParam.class);
        verify(milvus).search(request.capture());
        assertEquals("embedding", request.getValue().getVectorFieldName());
        assertEquals(KnowledgeService.COLLECTION, request.getValue().getCollectionName());
        assertEquals(List.of(new KnowledgeService.SearchHit(42, "星舟公司内部手册", 1, evidence, 0.91f)), hits);
    }

    private static FieldData stringField(String name, String value) {
        return FieldData.newBuilder().setFieldName(name).setType(DataType.VarChar)
                .setScalars(ScalarField.newBuilder().setStringData(StringArray.newBuilder().addData(value)))
                .build();
    }
}
