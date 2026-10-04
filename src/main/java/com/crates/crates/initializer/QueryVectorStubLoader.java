package com.crates.crates.initializer;

import com.crates.crates.service.ContentVectorService;
import com.crates.crates.service.QueryVectorRecord;
import com.crates.crates.service.QueryVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 실제 query 데이터가 들어오기 전까지 쓸 임시 query 벡터를 Qdrant query_vector 컬렉션에 넣는다.
 *
 * <p>추천·검색 보드의 제목은 콘텐츠 평균 벡터와 가장 가까운 query로만 정한다(BoardTitleService). 대신 쓸 고정
 * 제목이 없어서, query_vector가 비어 있으면 홈 추천과 검색 보드가 오류로 끝난다. 실제 query와 query_vector는
 * AI 개발자가 적재하는데 아직 없으므로, 콘텐츠 벡터마다 {@code copies}개를 복제해 채워 둔다. 제목은
 * "임시 제목 {id}"라 의미는 없고, 검색 경로와 부하의 모양만 실제와 같다.</p>
 *
 * <p>개발 환경은 1배(적재 수십 초), 부하 측정은 실제 예상 규모인 4배로 넣는다(application-loadtest.yaml).
 * query_vector가 작으면 제목 매칭 검색이 실제보다 싸서 Qdrant 자원 상한을 낮게 잡기 때문이다.
 * 실제 데이터가 들어오면 {@code ai.vectorstore.qdrant.query-stub.enabled}를 false로 바꾼다.</p>
 *
 * <p><b>왜 잡음을 섞나</b> — 원본과 같은 벡터가 여러 개 겹치면 HNSW 그래프가 실제 query 분포와 다르게 지어져
 * 검색 비용이 달라진다. 성분마다 표준편차 {@code noise}의 가우스 잡음을 더하고 다시 정규화한다.
 * 0.01이면 768차원에서 원본과의 코사인 유사도가 약 0.96이다. 씨앗을 point id로 고정해 매번 같은 값이 나온다.</p>
 *
 * <p><b>언제 넣나</b> — 콘텐츠 벡터를 바탕으로 만들므로 ContentVectorLoader(ApplicationRunner)가 끝난 뒤인
 * ApplicationReadyEvent에서 돈다. Qdrant에 볼륨이 없어 컨테이너가 다시 만들어지면 사라지므로 기동마다 개수를
 * 확인하고, 콘텐츠 point 수 × copies와 다를 때만 다시 넣는다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.vectorstore.qdrant.query-stub.enabled", havingValue = "true")
public class QueryVectorStubLoader {

    /** payload의 model_version. 실제 데이터와 섞이지 않았는지 이 값으로 가른다. */
    static final String MODEL_VERSION = "query-stub";

    /** 한 번에 upsert할 point 수. 768차원 기준 약 3MB로, 조회 쪽 gRPC 한도와 같은 크기에 맞췄다. */
    private static final int UPSERT_BATCH_SIZE = 1000;

    private final ContentVectorService contentVectorService;
    private final QueryVectorService queryVectorService;

    @Value("${ai.server.embedding-dimension}")
    private int vectorDimension;

    @Value("${ai.vectorstore.qdrant.query-stub.copies:1}")
    private int copies;

    @Value("${ai.vectorstore.qdrant.query-stub.noise:0.01}")
    private double noise;

    @EventListener(ApplicationReadyEvent.class)
    public void load()
    {
        long contentPoints = contentVectorService.countPoints();
        if (contentPoints == 0)
        {
            log.warn("content_vector가 비어 있어 임시 query 벡터를 만들지 않습니다.");
            return;
        }

        long expected = contentPoints * copies;
        queryVectorService.ensureCollection(vectorDimension);
        if (queryVectorService.countPoints() == expected)
        {
            // 부하 측정 스크립트(load-test/lib/common.sh)가 이 문구로 적재 완료를 판단한다. 건너뛸 때도 같은 문구를 남긴다.
            log.info("Qdrant query vector stub completed (이미 적재됨): points={}", expected);
            return;
        }

        queryVectorService.recreateCollection(vectorDimension);

        AtomicLong loaded = new AtomicLong();
        contentVectorService.forEachVectorPage(page -> {
            List<QueryVectorRecord> batch = new ArrayList<>(UPSERT_BATCH_SIZE);
            page.forEach((contentId, vector) -> {
                for (int copy = 0; copy < copies; copy++)
                {
                    long queryId = contentId * copies + copy;
                    batch.add(new QueryVectorRecord(queryId, "임시 제목 " + queryId, jitter(vector, queryId), MODEL_VERSION));
                    if (batch.size() == UPSERT_BATCH_SIZE)
                    {
                        queryVectorService.upsertAll(batch);
                        loaded.addAndGet(batch.size());
                        batch.clear();
                    }
                }
            });
            queryVectorService.upsertAll(batch);
            loaded.addAndGet(batch.size());
        });

        log.info("Qdrant query vector stub completed: contentPoints={}, copies={}, points={}",
                contentPoints, copies, queryVectorService.countPoints());
        if (loaded.get() != expected)
        {
            log.warn("임시 query 벡터 수가 기대와 다릅니다. expected={}, loaded={}", expected, loaded.get());
        }
    }

    private float[] jitter(float[] vector, long seed)
    {
        SplittableRandom random = new SplittableRandom(seed);
        float[] result = new float[vector.length];
        double norm = 0;
        for (int i = 0; i < vector.length; i++)
        {
            result[i] = (float) (vector[i] + noise * random.nextGaussian());
            norm += result[i] * result[i];
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < result.length; i++)
        {
            result[i] *= scale;
        }
        return result;
    }
}
