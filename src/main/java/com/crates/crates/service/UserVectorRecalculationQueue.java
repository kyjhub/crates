package com.crates.crates.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 좋아요가 바뀐 사용자의 취향 벡터 재계산을 모아서, 동시에 정해진 수만큼만 돌린다.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>예전에는 좋아요·취소마다 가상 스레드 하나가 재계산을 바로 돌렸다. 재계산은 좋아요한 보드 전부의
 * 콘텐츠 벡터를 Qdrant에서 받아 오므로(1개월차 가입자면 최대 4,320개) Qdrant CPU를 많이 쓴다.
 * 상한이 없으니 좋아요가 몰리면 재계산이 Qdrant를 다 차지했다. 2026-10-04 부하 측정에서 좋아요→취소를
 * 초당 5쌍만 보내도 Qdrant 1코어가 100%에 붙었고, 같은 Qdrant를 쓰는 추천까지 함께 느려졌다.</p>
 *
 * <h2>어떻게</h2>
 *
 * <ul>
 *   <li><b>사용자별로 합친다.</b> 재계산은 좋아요 집합 전체를 다시 보므로, 같은 사용자의 재계산이
 *       밀려 있으면 한 번만 돌면 된다. 이미 대기 중이거나 도는 중이면 "다시 돌 것"만 표시한다.
 *       그래서 대기열 길이는 사용자 수를 넘지 않는다.</li>
 *   <li><b>한 사용자는 한 번에 하나만 돈다.</b> 예전에는 좋아요와 취소의 재계산이 동시에 돌다가
 *       먼저 시작한 쪽이 늦게 끝나면 낡은 값이 마지막에 저장될 수 있었다.</li>
 *   <li><b>동시에 도는 재계산 수에 상한을 둔다</b>({@code ai.user-vector.recalculation-concurrency}).
 *       재계산은 응답 뒤에 도는 작업이라 조금 늦어도 되지만, 추천은 사용자가 기다린다.
 *       Qdrant를 읽기 경로에 남겨 두는 것이 목적이다.</li>
 * </ul>
 *
 * <p>대기 중인 작업은 메모리에만 있어 재시작하면 사라진다. 사라진 재계산은 백필이 DB 상태로 찾아
 * 다시 한다(UserVectorBackfillService) — 실패를 따로 기억하지 않는 기존 설계가 그대로 성립한다.</p>
 */
@Slf4j
@Component
public class UserVectorRecalculationQueue {

    /** 대기 중이거나 도는 중인 사용자. 값이 true면 도는 사이에 좋아요가 또 바뀌어 한 번 더 돌아야 한다. */
    private final ConcurrentHashMap<Long, Boolean> dirtyByUserId = new ConcurrentHashMap<>();

    private final UserVectorService userVectorService;
    private final UserVectorMetrics metrics;
    private final ExecutorService workers;

    public UserVectorRecalculationQueue(UserVectorService userVectorService,
                                        UserVectorMetrics metrics,
                                        MeterRegistry registry,
                                        @Value("${ai.user-vector.recalculation-concurrency}") int concurrency)
    {
        this.userVectorService = userVectorService;
        this.metrics = metrics;
        // Qdrant와 DB를 기다리는 시간이 대부분이라 가상 스레드를 쓴다. 상한은 스레드 수가 정한다.
        this.workers = Executors.newFixedThreadPool(concurrency,
                Thread.ofVirtual().name("user-vector-recalc-", 0).factory());

        Gauge.builder("crates.user.vector.recalculation.pending", dirtyByUserId, ConcurrentHashMap::size)
                .description("재계산을 기다리거나 도는 중인 사용자 수. 계속 늘면 동시 재계산 상한이 좋아요 속도를 못 따라간다는 뜻이다.")
                .register(registry);
    }

    /** 이 사용자의 재계산을 요청한다. 이미 대기 중이거나 도는 중이면 한 번 더 돌도록 표시만 한다. */
    public void request(Long userId)
    {
        if (dirtyByUserId.putIfAbsent(userId, Boolean.FALSE) == null)
        {
            workers.execute(() -> run(userId));
        }
        else
        {
            dirtyByUserId.put(userId, Boolean.TRUE);
        }
    }

    private void run(Long userId)
    {
        do
        {
            // 계산을 시작하기 전에 표시를 지운다. 계산하는 사이에 들어온 요청은 다시 true로 만들어
            // 아래 remove가 실패하고 한 번 더 돈다.
            dirtyByUserId.put(userId, Boolean.FALSE);
            recalculate(userId);
        }
        while (!dirtyByUserId.remove(userId, Boolean.FALSE));
    }

    private void recalculate(Long userId)
    {
        try
        {
            userVectorService.recalculateFor(userId);
        }
        catch (Exception e)
        {
            // 좋아요는 이미 커밋됐고 사용자는 응답을 받았다. 재시도하지 않는다 — 다음 좋아요나 백필이
            // 좋아요 집합 전체로 다시 계산해 맞춘다. 대신 지표와 로그로 남긴다.
            metrics.recordFailure();
            log.error("취향 벡터 재계산에 실패했습니다. userId: {}", userId, e);
        }
    }

    @PreDestroy
    void shutdown()
    {
        workers.shutdownNow();
    }
}
