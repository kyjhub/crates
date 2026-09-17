package com.crates.crates.repository;

import com.crates.crates.DTO.LikedBoardDto;
import com.crates.crates.entity.board.Board;
import com.crates.crates.entity.board.BoardFeedback;
import com.crates.crates.enumData.Rating;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BoardFeedbackRepository extends JpaRepository<BoardFeedback, Long> {

    boolean existsByBoardIdAndUserIdAndRating(Long boardId, Long userId, Rating rating);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM BoardFeedback f WHERE f.board.id = :boardId AND f.user.id = :userId AND f.rating = :rating")
    int deleteFeedback(@Param("boardId") Long boardId,
                       @Param("userId") Long userId,
                       @Param("rating") Rating rating);

    /**
     * 이 사용자가 좋아요한 보드 전부를 좋아요한 시각과 함께.
     *
     * <p>취향 벡터를 다시 계산할 때 쓴다. 삭제된 보드는 제외한다 — 사라진 보드가 취향에
     * 계속 영향을 주면 안 된다.</p>
     *
     * <p><b>"살아있는 보드"를 조인하지 않고 "삭제된 보드"를 배제한다.</b> 결과는 같지만 비용의
     * 차수가 다르다. 이전에는 {@code f.board.deletedAt IS NULL}로 적어 board와 조인했는데,
     * 살아있는 보드가 사실상 전부라 플래너가 board 전체를 훑거나 좋아요 건수만큼 PK를 조회했다.
     * 즉 비용이 <b>O(전체 보드 수)</b>였다. 반대로 삭제된 보드는 극소수이므로, 그쪽을 인덱싱해
     * 배제하면 비용이 <b>O(삭제된 보드 수)</b>가 된다.</p>
     *
     * <p>실측(board 100,010행 · 삭제 100건 · 좋아요 159건, 제네릭 플랜 강제):</p>
     * <pre>
     *   조인 방식      Nested Loop -> board_pkey 159회 조회      buffers 480   0.629ms
     *   이 방식        Hash Anti Join -> 삭제된 100건만 읽음     buffers   8   0.090ms
     * </pre>
     *
     * <p>{@code idx_board_deleted}(V8)가 이 배제를 받친다. {@code deleted_at IS NOT NULL}은
     * 쿼리에 상수로 박혀 있어 플래너가 부분 인덱스 매칭을 증명할 수 있다 — V7의 규칙과 같다.</p>
     *
     * <p>서브쿼리에서 {@code f.board}가 아니라 {@code f.board.id}를 쓰는 것이 중요하다.
     * 전자는 board로 조인을 유발해 없애려던 비용이 되돌아온다. 후자는 FK 컬럼을 그대로 쓴다.</p>
     */
    @Query("SELECT new com.crates.crates.DTO.LikedBoardDto(f.board.id, f.createdAt) " +
           "FROM BoardFeedback f " +
           "WHERE f.user.id = :userId AND f.rating = :rating " +
           "  AND NOT EXISTS (SELECT 1 FROM Board b " +
           "                  WHERE b.id = f.board.id AND b.deletedAt IS NOT NULL)")
    List<LikedBoardDto> findLikedBoardsWithTime(@Param("userId") Long userId,
                                                @Param("rating") Rating rating);

    /**
     * 보관함 "좋아요" 탭 — 내가 좋아요한 보드를 최근에 누른 순으로.
     *
     * <p>보드가 아니라 <b>board_feedback에서 출발한다.</b> 정렬 기준이 "내가 언제 눌렀는지"라
     * f.createdAt이 필요하고, 그 값은 좋아요 행에만 있다.</p>
     *
     * <p>동률 기준이 b.id가 아니라 <b>f.id</b>인 이유: 좋아요 행 id는 누른 순서와 같은 방향이라
     * 의미가 정렬 축과 맞고, idx_feedback_user_recent (user_id, rating, created_at DESC, id DESC)의
     * 마지막 컬럼이라 정렬이 통째로 인덱스로 해결된다. b.id는 다른 테이블 컬럼이라 인덱스가
     * 커버하지 못해 별도 정렬이 붙는다.</p>
     *
     * <p>rating 조건은 성능 때문이 아니라 정확성 때문에 있다. 지금은 LIKE만 저장되지만
     * Rating에 DISLIKE가 있어, 싫어요를 쓰기 시작하면 그 보드들이 보관함에 "좋아요한 보드"로
     * 섞여 들어온다. 겸사겸사 위 인덱스의 매칭 조건이기도 하다.</p>
     */
    @Query("SELECT b FROM BoardFeedback f JOIN f.board b " +
           "WHERE f.user.id = :userId AND f.rating = :rating AND b.deletedAt IS NULL " +
           "ORDER BY f.createdAt DESC, f.id DESC")
    List<Board> findLikedBoards(@Param("userId") Long userId,
                                @Param("rating") Rating rating,
                                Pageable pageable);

    /** 화면에 보이는 보드들 중 내가 좋아요한 것만 추려낸다. 보드마다 조회하지 않기 위한 일괄 조회. */
    @Query("SELECT f.board.id FROM BoardFeedback f " +
           "WHERE f.user.id = :userId AND f.rating = :rating AND f.board.id IN :boardIds")
    List<Long> findLikedBoardIds(@Param("userId") Long userId,
                                 @Param("rating") Rating rating,
                                 @Param("boardIds") List<Long> boardIds);
}
