package com.crates.crates.initializer;

import com.crates.crates.repository.ContentRepository;
import com.crates.crates.service.ContentVectorRecord;
import com.crates.crates.service.ContentVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 로컬 개발용 Qdrant 더미 벡터 시더.
 *
 * <p>관계형 DB의 content.id를 Qdrant point id 및 content_id payload로 사용한다.
 * 동일한 content id에는 항상 동일한 float32 벡터가 생성되며, 이미 존재하는 point는
 * 실제 임베딩일 수 있으므로 덮어쓰지 않는다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "ai.vectorstore.qdrant.seed.enabled",
        havingValue = "true"
)
public class ContentVectorSeeder implements ApplicationRunner {

    private final ContentRepository contentRepository;
    private final ContentVectorService contentVectorService;

    @Value("${ai.vectorstore.qdrant.seed.dimension:768}")
    private int vectorDimension;

    @Value("${ai.vectorstore.qdrant.seed.batch-size:256}")
    private int batchSize;

    @Value("${ai.vectorstore.qdrant.seed.random-seed:20260830}")
    private long randomSeed;

    @Override
    public void run(ApplicationArguments args) {
        validateConfiguration();

        // Hibernate/Flyway의 관계형 데이터 초기화가 끝난 뒤 실제 content 개수를 기준으로 시딩한다.
        long contentCount = contentRepository.count();
        if (contentCount == 0) {
            log.warn("Qdrant content vector seed skipped because the content table is empty.");
            return;
        }

        // 빈 Qdrant에서도 실행될 수 있도록 시딩 전에 768차원 컬렉션을 준비한다.
        contentVectorService.ensureCollection(vectorDimension);

        long lastContentId = 0L;
        long insertedCount = 0L;
        long skippedCount = 0L;

        while (true) {
            // 전체 콘텐츠를 메모리에 올리지 않고 ID 오름차순으로 일정 개수씩 처리한다.
            List<Long> contentIds = contentRepository.findContentIdsAfter(
                    lastContentId,
                    PageRequest.of(0, batchSize)
            );
            if (contentIds.isEmpty()) {
                break;
            }

            Set<Long> existingIds = contentVectorService.findExistingContentIds(contentIds);
            List<ContentVectorRecord> records = new ArrayList<>(contentIds.size() - existingIds.size());

            // 기존 point는 유지하고 아직 없는 content ID에 대해서만 더미 벡터를 만든다.
            for (Long contentId : contentIds) {
                if (existingIds.contains(contentId)) {
                    skippedCount++;
                    continue;
                }
                records.add(new ContentVectorRecord(contentId, createDeterministicVector(contentId)));
            }

            contentVectorService.upsertAll(records);
            insertedCount += records.size();
            lastContentId = contentIds.getLast();

            log.info(
                    "Qdrant content vector seed progress: processed={}/{}, inserted={}, skipped={}",
                    insertedCount + skippedCount,
                    contentCount,
                    insertedCount,
                    skippedCount
            );
        }

        log.info(
                "Qdrant content vector seed completed: contentCount={}, inserted={}, skipped={}, dimension={}",
                contentCount,
                insertedCount,
                skippedCount,
                vectorDimension
        );
    }

    // 벡터 차원과 배치 크기가 시딩 계약에 맞는지 실제 Qdrant 호출 전에 확인한다.
    private void validateConfiguration() {
        if (vectorDimension != 768) {
            throw new IllegalStateException("Dummy content vector dimension must be 768: " + vectorDimension);
        }
        if (batchSize <= 0) {
            throw new IllegalStateException("Qdrant seed batch-size must be greater than zero: " + batchSize);
        }
    }

    /**
     * content ID와 고정 seed를 기반으로 재현 가능한 768차원 float32 벡터를 만든다.
     * Cosine 유사도 검색에 사용할 수 있도록 마지막에 L2 정규화를 적용한다.
     */
    private float[] createDeterministicVector(long contentId) {
        SplittableRandom random = new SplittableRandom(randomSeed ^ mix64(contentId));
        float[] vector = new float[vectorDimension];
        double squaredSum = 0.0;

        for (int index = 0; index < vector.length; index++) {
            float value = (float) random.nextDouble(-1.0, 1.0);
            vector[index] = value;
            squaredSum += (double) value * value;
        }

        float norm = (float) Math.sqrt(squaredSum);
        for (int index = 0; index < vector.length; index++) {
            vector[index] = vector[index] / norm;
        }
        return vector;
    }

    // 서로 가까운 content ID도 충분히 다른 난수 시퀀스를 갖도록 64비트 ID를 혼합한다.
    private long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
