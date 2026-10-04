package com.crates.crates.service;

import com.crates.crates.qdrant.PointRecord;
import com.crates.crates.qdrant.QdrantPointOperations;
import com.crates.crates.qdrant.VectorPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class QueryVectorService {

    private final QdrantPointOperations pointOperations;

    @Value("${ai.vectorstore.qdrant.query-collection-name}")
    private String collectionName;

    public void ensureCollection(int vectorDimension) {
        pointOperations.ensureCollection(collectionName, vectorDimension);
    }

    public void recreateCollection(int vectorDimension) {
        pointOperations.deleteCollection(collectionName);
        pointOperations.ensureCollection(collectionName, vectorDimension);
    }

    public long countPoints() {
        return pointOperations.count(collectionName);
    }

    public void upsert(QueryVectorRecord record) {
        upsertAll(List.of(record));
    }

    // 보드 제목과 벡터, 그 벡터를 만든 모델 버전을 함께 저장한다. 버전은 쓸 때만 필요하다.
    public void upsertAll(List<QueryVectorRecord> records) {
        List<PointRecord> points = records.stream()
                .map(record -> new PointRecord(record.queryId(), record.vector(), payloadOf(record)))
                .toList();

        pointOperations.upsertPoints(collectionName, points);
    }

    private Map<String, Object> payloadOf(QueryVectorRecord record) {
        return Map.of(
                VectorPayload.QUERY, record.queryText(),
                VectorPayload.MODEL_VERSION,
                VectorPayload.requireModelVersion(record.modelVersion(), "queryId " + record.queryId())
        );
    }

    public Optional<QueryMatch> findMostSimilarQuery(float[] targetVector) {
        return pointOperations.search(collectionName, targetVector, 1)
                .stream()
                .findFirst()
                .map(result -> new QueryMatch(
                        result.id(),
                        (String) result.payload().get(VectorPayload.QUERY),
                        result.score()
                ));
    }
}
