package com.crates.crates.repository;

import com.crates.crates.entity.user.UserVector;
import com.crates.crates.enumData.Rating;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface UserVectorRepository extends JpaRepository<UserVector, Long> {

    /**
     * 밀린 사용자들의 id. 백필이 고칠 대상이다.
     *
     * <p><b>이 쿼리 하나가 두 가지 일을 한다.</b> 돌려준 목록의 크기는 {@code stale.users} 게이지가
     * 되고, 목록 자체는 백필이 고칠 대상이 된다. 예전에는 세는 쿼리와 고칠 목록을 따로 두었는데
     * 조건이 같아서 거의 같은 스캔을 두 번 하고 있었다.</p>
     *
     * <p>실패를 따로 기록해 둘 필요가 없다는 것이 이 설계의 핵심이다. 재계산이 실패했는지는
     * DB 상태(벡터가 마지막 좋아요보다 오래됐는가)만 보면 알 수 있고, 그래서 서버가 재시작되든
     * 배포가 끼어들든 대상이 유실되지 않는다.</p>
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

    /**
     * 다시 계산한 취향 벡터를 저장한다. 재계산(UserVectorService.recalculateFor)만 쓴다.
     *
     * <p>엔티티를 읽어 고치는 대신 UPDATE 한 번으로 쓰는 이유: 재계산은 Qdrant를 기다리는 동안 DB 커넥션을
     * 쥐지 않으려고 트랜잭션 밖에서 돈다. 그래서 앞에서 읽은 엔티티는 영속성 컨텍스트에 없고, 고쳐도 반영되지
     * 않는다. 이 쿼리가 자기 트랜잭션을 짧게 열고 바로 닫는다.</p>
     */
    @Transactional
    @Modifying
    @Query("UPDATE UserVector uv SET uv.userVector = :vector, uv.updatedAt = :updatedAt WHERE uv.userId = :userId")
    int updateVector(@Param("userId") Long userId,
                     @Param("vector") float[] vector,
                     @Param("updatedAt") LocalDateTime updatedAt);

}
