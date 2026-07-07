package com.crates.crates.service;

import com.crates.crates.qdrant.QdrantPointOperations;
import com.crates.crates.qdrant.PointRecord;
import com.crates.crates.qdrant.ScoredPointResult;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ContentVectorService {

    private final QdrantPointOperations pointOperations;

    @Value("${ai.vectorstore.qdrant.content-collection-name}")
    private String collectionName;

    public void upsert(Long contentId, float[] vector) {
        pointOperations.upsertPoint(collectionName, contentId, vector, Map.of());
    }

    public void upsertAll(List<ContentVectorRecord> records) {
        List<PointRecord> points = records.stream()
                .map(record -> new PointRecord(record.contentId(), record.vector(), Map.of()))
                .toList();

        pointOperations.upsertPoints(collectionName, points);
    }

    public List<Long> findSimilarContentIds(float[] targetVector, int topK) {
        return pointOperations.search(collectionName, targetVector, topK)
                .stream()
                .map(ScoredPointResult::id)
                .toList();
    }
}
