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
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ContentVectorService {

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

    // 이미 Qdrant에 저장된 content ID를 찾아 더미 벡터의 중복 저장을 방지한다.
    public Set<Long> findExistingContentIds(List<Long> contentIds) {
        return pointOperations.findExistingPointIds(collectionName, contentIds);
    }

    // Qdrant point ID와 payload의 content_id를 관계형 DB의 content ID로 통일해 저장한다.
    public void upsert(Long contentId, float[] vector) {
        pointOperations.upsertPoint(collectionName, contentId, vector, Map.of("content_id", contentId));
    }

    // 여러 콘텐츠 벡터를 Qdrant point로 변환해 배치 저장한다.
    public void upsertAll(List<ContentVectorRecord> records) {
        List<PointRecord> points = records.stream()
                .map(record -> new PointRecord(
                        record.contentId(),
                        record.vector(),
                        Map.of("content_id", record.contentId())
                ))
                .toList();

        pointOperations.upsertPoints(collectionName, points);
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
