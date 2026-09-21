package com.crates.crates.scheduler;

import com.crates.crates.service.UserVectorBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 재계산이 유실된 취향 벡터를 주기적으로 고친다.
 *
 * <p>왜 필요한지는 {@link UserVectorBackfillService} 주석 참고.</p>
 *
 * <p><b>{@code stale.users} 게이지도 이 주기로 갱신된다.</b> 백필이 고칠 대상을 찾는 쿼리가
 * 곧 그 게이지가 세려던 집합이라, 스케줄러를 따로 두면 거의 같은 스캔이 두 번 돈다.</p>
 *
 * <p>{@code fixedDelay}를 쓴다. 배치가 오래 걸리면 다음 실행도 그만큼 밀려서, 재계산이 느린
 * 상황에 실행이 겹쳐 쌓이지 않는다.</p>
 *
 * <p><b>인스턴스를 여러 대 띄우면 같은 사용자를 중복 처리한다.</b> 재계산이 멱등이라 결과는
 * 옳지만 Qdrant 왕복이 낭비된다. 다중화할 때는 리더 선출이나 분산 락을 앞에 둘 것.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserVectorBackfillScheduler {

    private final UserVectorBackfillService backfillService;

    @Scheduled(
            fixedDelayString = "${scheduler.user-vector-backfill.fixed-delay}",
            initialDelayString = "${scheduler.user-vector-backfill.initial-delay}")
    public void backfill() {
        try {
            backfillService.backfill();
        }
        catch (Exception e) {
            // 스케줄러 스레드로 예외가 새면 이후 주기가 돌지 않는 구현이 있다. 여기서 막는다.
            log.error("취향 벡터 백필 배치가 실패했습니다.", e);
        }
    }
}
