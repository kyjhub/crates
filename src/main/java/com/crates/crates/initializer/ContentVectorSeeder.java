package com.crates.crates.initializer;

import com.crates.crates.ai.DeterministicVectorFactory;
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

    /**
     * 시딩 전에 컬렉션을 지우고 다시 만들지 여부. 로컬에서 관계형 DB를 갈아엎을 때 켠다.
     * 실제 임베딩을 적재하기 시작하면 반드시 false로 되돌려야 한다.
     */
    @Value("${ai.vectorstore.qdrant.seed.recreate:false}")
    private boolean recreateCollection;

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
        if (recreateCollection) {
            // 관계형 DB를 초기화하면 content.id가 1부터 다시 부여된다. 이전 실행의 point가 남아 있으면
            // 아래 findExistingContentIds가 전부 "이미 있음"으로 판단해 건너뛰고,
            // 새 콘텐츠에 옛 벡터가 매달린 채 조용히 잘못된 추천이 나간다.
            log.warn("Qdrant 콘텐츠 벡터 컬렉션을 지우고 다시 만듭니다. "
                    + "실제 임베딩을 적재하기 시작하면 ai.vectorstore.qdrant.seed.recreate를 false로 되돌리세요.");
            contentVectorService.recreateCollection(vectorDimension);
        } else {
            contentVectorService.ensureCollection(vectorDimension);
        }

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
     * 검색어 스텁 임베딩(StubEmbeddingClient)과 같은 생성기를 쓰기 때문에
     * 두 벡터가 같은 공간에 놓이고 Cosine 검색이 성립한다.
     */
    private float[] createDeterministicVector(long contentId) {
        return DeterministicVectorFactory.create(
                randomSeed ^ DeterministicVectorFactory.mix64(contentId),
                vectorDimension
        );
    }
}
