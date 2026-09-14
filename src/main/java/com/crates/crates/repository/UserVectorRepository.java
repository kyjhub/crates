package com.crates.crates.repository;

import com.crates.crates.entity.user.UserVector;
import com.crates.crates.enumData.Rating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
     */
    @Query("SELECT count(uv) FROM UserVector uv " +
           "WHERE EXISTS (SELECT 1 FROM BoardFeedback f " +
           "              WHERE f.user.id = uv.userId " +
           "                AND f.rating = :rating " +
           "                AND f.createdAt > uv.updatedAt)")
    long countStaleVectors(@Param("rating") Rating rating);
}
