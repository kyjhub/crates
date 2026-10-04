package com.crates.crates.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 취향 벡터 재계산 관측 지표.
 *
 * <p>지표를 한 곳에 모으는 이유는 두 가지다. 이름이 여러 파일에 흩어지면 오타 하나로 지표가
 * 조용히 갈라지고, 서비스 코드에 계측이 섞이면 계산 로직을 읽기 어려워진다.</p>
 *
 * <p><b>왜 필요한가.</b> 2026-09-11에 Qdrant 클라이언트/서버 버전이 어긋나 벡터 조회가 빈 배열을
 * 돌려주는 동안, 좋아요를 눌러도 취향 벡터가 전혀 갱신되지 않았다. 그런데 HTTP 응답은 정상이고
 * 예외도 나지 않아 나흘 뒤에야 발견됐다. 아래 STALE_USERS 하나만 있었어도 즉시 드러났다.</p>
 *
 * <p>조회는 {@code GET /actuator/metrics/crates.user.vector.*} (인증 필요).</p>
 */
@Slf4j
@Component
public class UserVectorMetrics {

    /** 재계산 1건의 소요 시간. result 태그로 갱신/유지/건너뜀을 가른다. */
    private static final String RECALCULATION = "crates.user.vector.recalculation";

    /** 재계산 1건이 훑은 좋아요 보드 수. 재계산 비용이 이 값에 비례한다. */
    private static final String BOARDS = "crates.user.vector.recalculation.boards";

    /** 재계산이 예외로 끝난 횟수. */
    private static final String FAILURES = "crates.user.vector.recalculation.failures";

    /** 마지막 좋아요보다 벡터가 오래된 사용자 수. */
    private static final String STALE_USERS = "crates.user.vector.stale.users";

    /** 백필이 뒤늦게 고친 횟수. result 태그로 성공/실패를 가른다. */
    private static final String BACKFILL = "crates.user.vector.backfill";

    /** 조회 실패를 "밀린 사용자 0명"과 구분하기 위한 값. 아직 한 번도 계산하지 못한 상태도 이 값이다. */
    private static final double UNAVAILABLE = -1.0;

    private final MeterRegistry registry;
    private final Counter failures;

    /**
     * 마지막으로 계산해 둔 밀린 사용자 수.
     *
     * <p>백필 스케줄러가 쓰고 게이지 콜백이 읽는다. 서로 다른 스레드라 volatile 이다.
     * 최신성보다 일관성이 덜 중요한 값이라 그 이상은 필요 없다.</p>
     */
    private volatile double staleUsers = UNAVAILABLE;

    public UserVectorMetrics(MeterRegistry registry)
    {
        this.registry = registry;

        // 게이지는 "마지막으로 받아 적은 값"만 읽는다. 여기서 쿼리를 돌리면 안 된다 —
        // Micrometer 게이지는 스크레이프마다 평가되므로, Prometheus가 15초마다 긁으면
        // 쿼리도 15초마다 돈다. 비용이 min(O(전체 사용자), O(전체 좋아요))라 서비스가
        // 자랄수록 감시 장치가 부하 원인이 된다.
        //
        // 값을 넣어주는 쪽은 백필이다(UserVectorBackfillService). 백필이 고칠 대상을 찾는
        // 쿼리가 곧 이 게이지가 세려던 그 집합이라, 따로 세면 같은 스캔을 두 번 한다.
        Gauge.builder(STALE_USERS, this, m -> m.staleUsers)
                .description("마지막 좋아요보다 취향 벡터가 오래된 사용자 수(백필 batch-size 에서 잘림). "
                        + "백필이 매 주기 0으로 되돌리므로 0이 정상이고, 0이 아닌 상태가 이어지면 "
                        + "백필이 따라잡지 못하고 있다는 뜻이다. -1은 아직 세지 못했다는 뜻이다.")
                .register(registry);

        // 기동 시점에 0으로 만들어 둔다. Micrometer는 첫 증가 때 지표를 만들기 때문에,
        // 그냥 두면 "실패가 없다"와 "계측이 붙지 않았다"가 똑같이 '지표 없음'으로 보인다.
        // 실패 지표에서 그 둘을 헷갈리는 것은 침묵하는 장애를 정상으로 읽는 것과 같다.
        this.failures = Counter.builder(FAILURES)
                .description("취향 벡터 재계산이 예외로 끝난 횟수")
                .register(registry);
    }

    public Timer.Sample startRecalculation()
    {
        return Timer.start(registry);
    }

    /**
     * 재계산 소요 시간 기록.
     *
     * <p>주의: 이 값은 <b>메서드 본문</b>까지만 잰다. 실제 UPDATE는 트랜잭션이 커밋될 때
     * 일어나므로 여기에 포함되지 않는다. 끝에서 끝까지의 반영 지연은
     * {@code user_vector.updated_at - board_feedback.created_at}으로 재야 한다
     * (docs/load-test-and-metrics.md 4-1).</p>
     */
    public void recordRecalculation(Timer.Sample sample, Result result)
    {
        sample.stop(Timer.builder(RECALCULATION)
                .description("취향 벡터 재계산 1건의 소요 시간(커밋 제외)")
                .tag("result", result.tag)
                .register(registry));
    }

    /**
     * 이번 재계산이 훑은 좋아요 보드 수.
     *
     * <p>재계산은 매번 좋아요 집합 <b>전체</b>를 다시 읽으므로 비용이 이 값에 비례한다
     * (실측 보드 1건당 약 1ms). 분포가 오른쪽으로 밀리기 시작하면 증분 방식이나
     * 상한 도입을 검토할 시점이다.</p>
     */
    public void recordLikedBoards(int boardCount)
    {
        DistributionSummary.builder(BOARDS)
                .description("재계산 1건이 훑은 좋아요 보드 수")
                .register(registry)
                .record(boardCount);
    }

    /**
     * 재계산 실패 1건.
     *
     * <p>재계산 대기열(UserVectorRecalculationQueue)의 try/catch가 잡은 예외를 센다. 재계산은 트랜잭션 밖에서
     * 돌고 저장도 UPDATE 한 번이라 저장 실패까지 여기 잡힌다. 백필이 고친 실패는 {@code backfill} 카운터에 따로
     * 남으므로 STALE_USERS와 함께 봐야 한다.</p>
     */
    public void recordFailure()
    {
        failures.increment();
    }

    /**
     * 이번 주기에 발견한 밀린 사용자 수를 게이지에 적는다.
     *
     * <p>백필이 대상을 찾은 <b>직후</b>, 고치기 전에 부른다. 고친 뒤에 세면 언제나 0이라
     * 지표가 아무 말도 하지 않는다.</p>
     *
     * <p>값이 백필의 {@code batch-size}에서 잘린다는 점에 주의. 100이면 "100명 이상"이라는
     * 뜻이다. 이 지표로 하는 판단은 "0이냐 아니냐"뿐이라 문제되지 않는다.</p>
     *
     * <p><b>0이라고 해서 정상 경로가 멀쩡하다는 뜻은 아니다.</b> 백필이 매 주기 0으로 되돌리기
     * 때문이다. 정상 경로가 깨졌는지는 {@code backfill} 카운터로 본다 — 그쪽이 꾸준히 오르는데
     * 이 게이지가 0이면 "깨졌지만 백필이 막고 있다"는 상태다. 둘 다 0이 아니면 백필도
     * 따라잡지 못하고 있다는 뜻이고 그때가 심각하다.</p>
     */
    public void recordStaleUsers(int count)
    {
        staleUsers = count;
    }

    /** 세는 데 실패했다. 0으로 두면 "밀린 사용자 없음"과 구분되지 않아 침묵하는 장애를 정상으로 읽게 된다. */
    public void markStaleUsersUnavailable()
    {
        staleUsers = UNAVAILABLE;
    }

    /**
     * 백필 1건.
     *
     * <p>좋아요로 촉발된 재계산과 <b>구분해서</b> 센다. 이 값이 꾸준히 올라간다는 것은 정상 경로가
     * 자주 유실되고 있다는 뜻이라, {@code recalculation} 타이머만 봐서는 보이지 않는 신호다.
     * 반대로 0이 이어지면 백필이 할 일이 없다는 뜻이고 그것이 정상이다.</p>
     */
    public void recordBackfill(boolean succeeded)
    {
        Counter.builder(BACKFILL)
                .description("백필이 뒤늦게 고친 취향 벡터 수")
                .tag("result", succeeded ? "updated" : "failed")
                .register(registry)
                .increment();
    }

    /** 재계산이 어떻게 끝났는지. 소요 시간만 보면 "빨리 끝났다"와 "아무것도 안 했다"가 섞인다. */
    public enum Result {

        /** 새 벡터로 갱신됨. 정상 경로. */
        UPDATED("updated"),

        /** 쓸 수 있는 콘텐츠 벡터가 없어 기존 값을 유지함. 이어지면 Qdrant 쪽을 의심할 것. */
        UNCHANGED("unchanged"),

        /** user_vector 행이 없어 건너뜀. 가입 시 만들어지므로 정상 흐름에서는 나오지 않는다. */
        SKIPPED("skipped");

        private final String tag;

        Result(String tag)
        {
            this.tag = tag;
        }
    }
}
