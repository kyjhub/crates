package com.crates.crates.service;

import com.crates.crates.qdrant.QdrantPointOperations;
import com.crates.crates.qdrant.PointRecord;
import com.crates.crates.qdrant.ScoredPointResult;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ContentVectorService {

    /** point payload 키. point id와 같은 값이지만 payload 필터에 쓰려고 함께 싣는다. */
    public static final String PAYLOAD_CONTENT_ID = "content_id";

    /**
     * 벡터를 만든 AI 모델 체크포인트. content 벡터와 검색어·취향 벡터가 같은 모델 공간에
     * 있어야 Cosine 유사도가 의미를 갖는다. 모델을 바꿀 때 어느 point가 옛 모델인지 이 값으로 가른다.
     */
    public static final String PAYLOAD_MODEL_VERSION = "model_version";

    private final QdrantPointOperations pointOperations;

    @Value("${ai.vectorstore.qdrant.content-collection-name}")
    private String collectionName;

    // 콘텐츠 벡터 컬렉션을 생성하거나 기존 컬렉션의 차원을 검증한다.
    public void ensureCollection(int vectorDimension) {
        pointOperations.ensureCollection(collectionName, vectorDimension);
    }

    /**
     * 컬렉션을 지우고 다시 만든다.
     *
     * <p>관계형 DB를 초기화하면 content.id가 1부터 다시 부여되는데, Qdrant는 그 사실을 모른다.
     * 이전 실행의 point가 남아 있으면 시더가 "이미 있다"고 판단해 건너뛰고,
     * 결과적으로 새 content에 옛 벡터가 매달린 채 조용히 잘못된 추천이 나간다.
     * 로컬에서 DB를 갈아엎을 때 함께 호출한다.</p>
     */
    public void recreateCollection(int vectorDimension) {
        pointOperations.deleteCollection(collectionName);
        pointOperations.ensureCollection(collectionName, vectorDimension);
    }

    /** 컬렉션의 point 수. */
    public long countPoints() {
        return pointOperations.count(collectionName);
    }

    /** model_version이 주어진 값 중 하나인 point 수. */
    public long countPointsWithModelVersion(Collection<String> modelVersions) {
        return pointOperations.countMatching(collectionName, PAYLOAD_MODEL_VERSION, List.copyOf(modelVersions));
    }

    // Qdrant point ID와 payload의 content_id를 관계형 DB의 content ID로 통일해 저장한다.
    public void upsert(ContentVectorRecord record) {
        upsertAll(List.of(record));
    }

    // 여러 콘텐츠 벡터를 Qdrant point로 변환해 배치 저장한다.
    public void upsertAll(List<ContentVectorRecord> records) {
        List<PointRecord> points = records.stream()
                .map(record -> new PointRecord(record.contentId(), record.vector(), payloadOf(record)))
                .toList();

        pointOperations.upsertPoints(collectionName, points);
    }

    private Map<String, Object> payloadOf(ContentVectorRecord record) {
        // Map.of는 null 값을 받지 않아 버전이 비면 여기서 NPE로 멈춘다. 이유가 드러나도록 먼저 검사한다.
        if (record.modelVersion() == null || record.modelVersion().isBlank()) {
            throw new IllegalArgumentException("model_version 없이 콘텐츠 벡터를 저장할 수 없습니다. contentId: "
                    + record.contentId());
        }
        return Map.of(
                PAYLOAD_CONTENT_ID, record.contentId(),
                PAYLOAD_MODEL_VERSION, record.modelVersion()
        );
    }

    /**
     * 콘텐츠 id로 벡터를 가져온다. id마다 따로 묻지 않고 모아서 넘기되, gRPC 수신 한도 때문에
     * 내부에서 1,000개씩 나눠 요청한다(QdrantPointOperations 참고). Qdrant에 없는 id는 결과에서 빠진다.
     */
    public Map<Long, float[]> findVectors(Collection<Long> contentIds) {
        return pointOperations.retrieveVectors(collectionName, contentIds);
    }

    public List<Long> findSimilarContentIds(float[] targetVector, int topK) {
        return pointOperations.search(collectionName, targetVector, topK)
                .stream()
                .map(ScoredPointResult::id)
                .toList();
    }
}
