package com.crates.crates.scheduler;

import com.crates.crates.service.UserVectorMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 밀린 취향 벡터 수를 주기적으로 다시 센다.
 *
 * <p>이 스케줄러가 있는 이유는 <b>계산 주기와 관측 주기를 떼어놓기 위해서</b>다. Micrometer
 * 게이지는 값을 물어볼 때마다 콜백을 실행하므로, 콜백에서 쿼리를 돌리면 Prometheus 스크레이프
 * 주기(보통 15초)가 곧 쿼리 주기가 된다. 이 쿼리는 사용자마다 인덱스를 한 번씩 뒤져서 비용이
 * O(전체 사용자)다 — 사용자 10,001명에서 p95 10.6ms였고, 100만이면 1초에 가까워진다.
 * 감시 장치가 부하 원인이 되는 전형적인 모양이다.</p>
 *
 * <p>여기서 계산해 담아두면 스크레이프가 아무리 잦아도 DB는 이 주기로만 조회된다.
 * 지표의 의미는 그대로다 — 재계산이 밀렸는지는 분 단위로 알면 충분하고, 초 단위로 알아야 할
 * 이유가 없다.</p>
 *
 * <p>fixedDelay를 쓴다(fixedRate가 아니라). 조회가 늦어지면 다음 실행도 그만큼 밀려,
 * DB가 느린 상황에서 실행이 겹쳐 쌓이지 않는다.</p>
 */
@Component
@RequiredArgsConstructor
public class UserVectorStaleGaugeScheduler {

    private final UserVectorMetrics metrics;

    @Scheduled(
            fixedDelayString = "${scheduler.user-vector-stale-gauge.fixed-delay}",
            initialDelayString = "${scheduler.user-vector-stale-gauge.initial-delay}")
    public void refresh() {
        metrics.refreshStaleUsers();
    }
}
