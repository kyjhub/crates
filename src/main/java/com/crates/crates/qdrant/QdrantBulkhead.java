package com.crates.crates.qdrant;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Qdrant 호출(검색 + 벡터 조회)을 동시에 몇 개까지 보낼지 제한한다(bulkhead).
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>추천 1건은 Qdrant 검색을 최대 5번 한다(콘텐츠 1번 + 보드 제목 query 4번). 상한이 없으면 요청이 몰릴 때
 * 호출 수십 개가 Qdrant 코어 몇 개에 한꺼번에 몰리고, 모두가 코어를 나눠 쓰느라 각자 늦게 끝난다.
 * 사용자가 늘 때마다 Qdrant 코어를 늘리는 대신 같은 코어를 줄 세워 쓰려는 것이다.</p>
 *
 * <h2>검색과 재계산 조회가 같은 자리를 나눠 쓴다</h2>
 *
 * <p>처음에는 검색만 묶었다. 그러자 혼합 부하에서 묶이지 않은 재계산의 벡터 조회가 Qdrant를 더 가져가 검색 줄이
 * 길어졌고, 추천 단독(재계산 없음)에서는 좋아진 것이 혼합에서는 나빠졌다(2026-10-04 측정). 그래서 Qdrant를 부르는
 * 요청 경로의 호출을 모두 이 상한 안에 넣는다. 재계산의 동시 실행 수 상한(UserVectorRecalculationQueue)은 그대로 둔다 —
 * 재계산이 자리를 모두 차지하지 못하게 하는 두 번째 칸이다.</p>
 *
 * <h2>넘치면 기다린다 — 포기하지 않는다</h2>
 *
 * <p>보드 제목 매칭은 빼거나 다른 제목으로 대신할 수 없는 기능이다. 그래서 자리가 없으면 대신할 값을 돌려주지
 * 않고 자리가 날 때까지 기다린다. 기다리는 쪽은 가상 스레드라(spring.threads.virtual.enabled) 플랫폼 스레드를 붙잡지
 * 않는다 — 처음 측정에서는 플랫폼 스레드 200개가 대기로 바닥나 Qdrant를 쓰지 않는 API까지 멈췄다.
 * 끝없이 기다리지는 않는다. {@code acquire-timeout}을 넘기면 오류로 끝낸다 — 그 정도로 밀렸다면 응답 목표는 이미
 * 넘긴 상태다.</p>
 */
@Slf4j
@Component
public class QdrantBulkhead {

    private final Semaphore permits;
    private final Duration acquireTimeout;
    private final Timer waitTimer;

    public QdrantBulkhead(@Value("${ai.vectorstore.qdrant.bulkhead.max-concurrent}") int maxConcurrent,
                                @Value("${ai.vectorstore.qdrant.bulkhead.acquire-timeout}") Duration acquireTimeout,
                                MeterRegistry registry)
    {
        this.permits = new Semaphore(maxConcurrent, true);
        this.acquireTimeout = acquireTimeout;
        log.info("Qdrant 호출 상한: maxConcurrent={}, acquireTimeout={}", maxConcurrent, acquireTimeout);
        this.waitTimer = Timer.builder("crates.qdrant.call.wait")
                .description("Qdrant 호출 자리를 기다린 시간. 늘어나면 동시 호출 상한이 부하를 못 따라간다는 뜻이다.")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        Gauge.builder("crates.qdrant.call.waiting", permits, Semaphore::getQueueLength)
                .description("Qdrant 호출 자리를 기다리는 요청 수")
                .register(registry);
    }

    /** 자리가 나면 호출을 실행한다. acquire-timeout 안에 자리가 나지 않으면 IllegalStateException. */
    public <T> T run(Supplier<T> call)
    {
        long started = System.nanoTime();
        boolean acquired;
        try
        {
            acquired = permits.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant 호출 자리를 기다리다 중단됐습니다.", e);
        }
        waitTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);

        if (!acquired)
        {
            throw new IllegalStateException("Qdrant 호출이 밀려 " + acquireTimeout.toMillis() + "ms 안에 자리가 나지 않았습니다.");
        }
        try
        {
            return call.get();
        }
        finally
        {
            permits.release();
        }
    }
}
