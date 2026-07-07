package com.crates.crates.service;

import com.crates.crates.qdrant.PointRecord;
import com.crates.crates.qdrant.QdrantPointOperations;
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

    public void upsert(Long queryId, String queryText, float[] vector) {
        pointOperations.upsertPoint(collectionName, queryId, vector, Map.of("query", queryText));
    }

    public void upsertAll(List<QueryVectorRecord> records) {
        List<PointRecord> points = records.stream()
                .map(record -> new PointRecord(
                        record.queryId(),
                        record.vector(),
                        Map.of("query", record.queryText())
                ))
                .toList();

        pointOperations.upsertPoints(collectionName, points);
    }

    public Optional<QueryMatch> findMostSimilarQuery(float[] targetVector) {
        return pointOperations.search(collectionName, targetVector, 1)
                .stream()
                .findFirst()
                .map(result -> new QueryMatch(
                        result.id(),
                        (String) result.payload().get("query"),
                        result.score()
                ));
    }
}
