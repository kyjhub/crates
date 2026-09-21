package com.crates.crates.service;

import com.crates.crates.enumData.Rating;
import com.crates.crates.repository.UserVectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 재계산이 유실된 사용자의 취향 벡터를 뒤늦게 고친다.
 *
 * <h2>왜 재시도가 아니라 백필인가</h2>
 *
 * <p>좋아요 리스너는 실패해도 재시도하지 않는다. 재계산이 좋아요 집합 <b>전체</b>를 다시 보므로
 * 다음 좋아요 때 저절로 맞춰지기 때문이다. 그런데 그 자가 치유에는 조건이 있다 —
 * <b>그 사용자가 다시 좋아요를 눌러야 한다.</b> 한 번 누르고 그 재계산이 실패한 뒤 돌아오지 않는
 * 사용자는 낡은 벡터를 영원히 갖는다. 읽기 경로도 고쳐주지 않는다({@code resolveVector}는
 * 좋아요가 <i>없는</i> 사용자에게만 새 시작 벡터를 만든다).</p>
 *
 * <p>그 구멍을 재시도로는 메울 수 없다. 이유가 셋이다.</p>
 *
 * <ul>
 *   <li><b>재시도는 예외가 난 것만 잡는다.</b> 2026-09-11 Qdrant 버전 불일치 때는 벡터 조회가
 *       빈 배열을 돌려줬을 뿐 예외가 나지 않아 재계산이 "성공"으로 끝났다. 재시도는 발동조차
 *       하지 않는다. 백필은 <i>결과</i>(벡터가 실제로 갱신됐는가)를 보므로 그대로 잡는다.</li>
 *   <li><b>재시도는 실패를 기억해야 한다.</b> 대기 중이던 재시도는 배포나 프로세스 종료와 함께
 *       사라진다. 백필은 아무것도 기억하지 않는다 — 누가 밀렸는지는 DB 상태에서 유도된다.</li>
 *   <li><b>지속 장애에서 갈린다.</b> Qdrant가 10분 죽어 있으면 재시도는 몇 번 해보고 포기하고
 *       그동안의 좋아요가 전부 유실된다. 백필은 복구된 뒤 다음 주기에 자연히 따라잡는다.</li>
 * </ul>
 *
 * <p>둘은 대체재가 아니다. 재시도는 0.5초짜리 딸꾹질에 강하고 백필은 그 밖의 전부에 강하다.
 * 지금은 백필만 둔다 — 조용한 실패가 실제로 우리를 문 적이 있고, 재시도는 그것을 못 잡는다.</p>
 *
 * <h2>이 방식이 성립하는 조건</h2>
 *
 * <p><b>재계산이 멱등이다.</b> 좋아요 집합 전체를 다시 계산하므로 몇 번 돌려도 결과가 같다.
 * 리스너와 백필이 같은 사용자를 동시에 처리해도 마지막 값이 옳다.</p>
 *
 * <p><b>대상을 상태에서 유도할 수 있다.</b> {@code stale.users} 게이지가 세는 조건이 곧 명단이다.
 * 세는 쿼리의 반환값만 {@code count(*)}에서 {@code user_id}로 바꾼 것이다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserVectorBackfillService {

    private final UserVectorRepository userVectorRepository;
    private final UserVectorService userVectorService;
    private final UserVectorMetrics metrics;

    /**
     * 한 번에 고칠 최대 인원.
     *
     * <p>상한을 두는 이유는 크게 망가졌을 때를 대비해서다. 재계산 1건이 Qdrant 왕복을 포함해
     * 수십~수백 ms라, 수만 명이 밀린 상태에서 상한이 없으면 한 번 돌기 시작한 배치가 몇 시간을
     * 붙잡는다. 못 고친 사람은 다음 주기가 이어받는다 — 오래 밀린 순으로 가져오므로 진행이 밀린다.</p>
     */
    @Value("${scheduler.user-vector-backfill.batch-size}")
    private int batchSize;

    /**
     * 이만큼 지난 좋아요부터 백필 대상으로 본다.
     *
     * <p>재계산은 커밋 직후 비동기로 돌기 때문에 방금 누른 좋아요는 아직 처리 중일 수 있다.
     * 유예가 없으면 백필이 진행 중인 작업을 쫓아다니며 같은 계산을 두 번 한다.</p>
     */
    @Value("${scheduler.user-vector-backfill.grace}")
    private Duration grace;

    /**
     * 밀린 사용자를 찾아 재계산한다.
     *
     * <p><b>이 메서드에 {@code @Transactional}을 붙이면 안 된다.</b> 붙이면 아래 루프가 하나의
     * 트랜잭션이 되고, {@code recalculateFor}(REQUIRED)가 거기 합류한다. 그러면 한 사용자에서
     * 난 예외가 배치 전체를 롤백시켜 <b>앞서 고친 것까지 되돌린다.</b> 트랜잭션 경계는 사용자
     * 한 명이어야 한다.</p>
     *
     * <p>사용자마다 예외를 잡고 계속 간다. 한 명이 망가졌다고 나머지를 포기할 이유가 없다.</p>
     *
     * @return 고친 인원
     */
    public int backfill() {
        List<Long> staleUserIds = userVectorRepository.findStaleUserIds(
                Rating.LIKE, LocalDateTime.now().minus(grace), PageRequest.of(0, batchSize));

        if (staleUserIds.isEmpty()) {
            return 0;
        }

        int repaired = 0;
        for (Long userId : staleUserIds) {
            try {
                // 다른 빈을 거치므로 프록시가 살아 있다. 사용자 한 명이 트랜잭션 하나다.
                userVectorService.recalculateFor(userId);
                metrics.recordBackfill(true);
                repaired++;
            }
            catch (Exception e) {
                metrics.recordBackfill(false);
                log.error("취향 벡터 백필에 실패했습니다. userId: {}", userId, e);
            }
        }

        log.info("취향 벡터 백필: 대상 {}명 중 {}명 복구", staleUserIds.size(), repaired);
        return repaired;
    }
}
