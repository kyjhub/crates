package com.crates.crates.repository;

import com.crates.crates.entity.user.UserVector;
import com.crates.crates.enumData.Rating;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface UserVectorRepository extends JpaRepository<UserVector, Long> {

    /**
     * 취향 벡터가 마지막 좋아요보다 오래된 사용자 수. 재계산이 밀렸거나 유실됐다는 신호다.
     *
     * <p>0이 정상이다. 재계산은 커밋 직후 비동기로 도므로 폭주 중에는 잠깐 0이 아닐 수 있지만,
     * 계속 0이 아니면 재계산이 아예 돌지 않고 있는 것이다.</p>
     *
     * <p>"마지막 좋아요를 찾아 비교"가 아니라 <b>"벡터보다 새로운 좋아요가 존재하는가"</b>로 쓴다.
     * 결과는 같은데 정렬이 필요 없어 EXISTS가 첫 일치에서 멈춘다.
     * idx_feedback_user_recent (user_id, rating, created_at DESC, id DESC)를 그대로 탄다.</p>
     *
     * <p><b>비용이 O(전체 사용자)다.</b> 사용자 1,001명에서 p95 1.4ms, 10,001명에서 10.6ms로
     * 정확히 선형이다. 이것을 {@code LIMIT}으로 잘라보려 했지만 되지 않는다 — LIMIT은 조건에
     * <i>맞는</i> 행을 그만큼 찾았을 때 멈추는데, 정상 상태에서는 밀린 사용자가 0명이라 멈출
     * 지점이 없다. 끝까지 훑는 것은 똑같고 계획만 Nested Loop로 바뀌어 블록이 926 → 30,095로
     * 오히려 늘었다(사용자 10,001명 실측).</p>
     *
     * <p>그래서 호출 빈도를 낮추는 쪽으로 풀었다. {@code UserVectorStaleGaugeScheduler}가
     * 주기적으로 한 번만 부르고 게이지는 그 값을 읽는다. 비용의 차수까지 낮추려면 "전체 사용자를
     * 훑는" 방향을 "최근 좋아요에서 출발하는" 방향으로 뒤집어야 하는데, 그건 인덱스 추가를
     * 동반하므로 따로 판단한다(실측 186블록 / 2.0ms).</p>
     */
    /**
     * 밀린 사용자들의 id. 백필이 고칠 대상이다.
     *
     * <p>위 {@code countStaleVectors}와 <b>같은 조건</b>이다. 세는 쿼리가 곧 고칠 명단이라는 점이
     * 이 설계의 핵심이다 — 실패를 따로 기록해 둘 필요가 없다. 재계산이 실패했는지는 DB 상태
     * (벡터가 마지막 좋아요보다 오래됐는가)만 보면 알 수 있고, 그래서 서버가 재시작되든 배포가
     * 끼어들든 대상이 유실되지 않는다.</p>
     *
     * <p><b>{@code before}로 유예를 둔다.</b> 재계산은 커밋 직후 비동기로 돌기 때문에, 방금 누른
     * 좋아요는 아직 처리 중일 수 있다. 그것까지 밀린 것으로 보면 백필이 진행 중인 작업을 쫓아다니며
     * 같은 계산을 두 번 한다. 조건을 "벡터보다 새롭고 <i>동시에</i> 충분히 오래된 좋아요가 있는가"로
     * 두어 처리 중인 건을 건너뛴다.</p>
     *
     * <p>오래 밀린 순서로 돌려준다. 한 번에 다 고치지 못하더라도 가장 오래 방치된 사용자부터
     * 줄어들고, 진행 상황이 {@code stale.users} 게이지에 단조롭게 반영된다.</p>
     */
    @Query("SELECT uv.userId FROM UserVector uv " +
           "WHERE EXISTS (SELECT 1 FROM BoardFeedback f " +
           "              WHERE f.user.id = uv.userId " +
           "                AND f.rating = :rating " +
           "                AND f.createdAt > uv.updatedAt " +
           "                AND f.createdAt < :before) " +
           "ORDER BY uv.updatedAt ASC")
    List<Long> findStaleUserIds(@Param("rating") Rating rating,
                                @Param("before") LocalDateTime before,
                                Pageable pageable);

    @Query("SELECT count(uv) FROM UserVector uv " +
           "WHERE EXISTS (SELECT 1 FROM BoardFeedback f " +
           "              WHERE f.user.id = uv.userId " +
           "                AND f.rating = :rating " +
           "                AND f.createdAt > uv.updatedAt)")
    long countStaleVectors(@Param("rating") Rating rating);
}
