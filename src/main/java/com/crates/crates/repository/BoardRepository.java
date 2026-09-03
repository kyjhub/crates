package com.crates.crates.repository;

import com.crates.crates.entity.board.Board;
import com.crates.crates.enumData.BoardType;
import com.crates.crates.enumData.Rating;
import com.crates.crates.enumData.Visibility;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BoardRepository extends JpaRepository<Board, Long> {
    /**
     * 인기 보드 후보를 좋아요 많은 순으로 가져온다.
     *
     * <p>PRIVATE 보드는 소유자만 보는 것이므로 제외한다. 정렬 마지막에 id를 두는 이유는,
     * like_count가 초기에 0~2에 몰려 동률이 대량으로 생기기 때문이다. id가 없으면 SQL이
     * 동률 간 순서를 보장하지 않아 LIMIT 경계에서 어떤 보드가 잘리는지가 실행마다 달라지고,
     * 그 결과 아래 signature 중복 제거 결과까지 흔들린다.
     * (좋아요는 UPDATE라 PostgreSQL MVCC에서 행의 물리 위치가 바뀌므로 실제로 자주 흔들린다.)
     *
     * <p>idx_board_popular가 (like_count DESC, id)로 잡혀 있어 정렬 연산 없이 인덱스만 읽는다.</p>
     */
    @Query("SELECT b FROM Board b " +
           "WHERE b.visibility = :visibility AND b.deletedAt IS NULL " +
           "ORDER BY b.likeCount DESC, b.id ASC")
    List<Board> findPopularBoards(@Param("visibility") Visibility visibility, Pageable pageable);

    /**
     * 콘텐츠 구성이 같은 보드를 찾는다.
     *
     * <p>AI_RECOMMEND(검색 보드 / 사용자 추천 보드)는 소유자가 없는 전역 공용 보드다.
     * 같은 구성이면 같은 보드로 취급해야 좋아요가 여러 행으로 갈라지지 않는다.
     * uk_board_ai_signature가 DB에서도 같은 규칙을 강제한다.</p>
     */
    @Query("SELECT b FROM Board b " +
           "WHERE b.boardType = :boardType AND b.contentSignature = :signature AND b.deletedAt IS NULL")
    Optional<Board> findActiveByTypeAndSignature(@Param("boardType") BoardType boardType,
                                                 @Param("signature") String signature);

    /**
     * "내 보드" — 내가 만든 보드와 내가 좋아요한 보드를 합쳐서 좋아요 많은 순으로.
     *
     * <p>이 서비스에서 좋아요는 사실상 "내 크레이트에 담기"다. 추천·검색 보드는 좋아요를 누른
     * 시점에 저장되고, 그걸 고치면 내 USER_CUSTOM 보드가 된다. 두 경로로 모인 보드가 한 곳에
     * 보여야 사용자가 "내가 모은 것"을 한눈에 본다.</p>
     *
     * <p>공개 여부는 걸지 않는다. 내 PRIVATE 보드는 나에게는 보여야 하고, 남의 PRIVATE 보드는
     * 애초에 좋아요할 수 없으니 여기 들어올 수 없다.</p>
     *
     * <p>소유자 조인을 <b>명시적 LEFT JOIN</b>으로 쓴다. b.user.id로 적으면 구현체에 따라
     * 암묵적 INNER JOIN이 되어, 소유자가 없는 전역 공용 보드(AI_RECOMMEND, PRE_MADE)가
     * 좋아요 조건을 만족해도 통째로 빠질 수 있다. 에러가 아니라 "일부가 안 보이는" 증상으로만
     * 드러나므로 애매하게 두지 않는다.</p>
     */
    @Query("SELECT b FROM Board b LEFT JOIN b.user owner " +
           "WHERE b.deletedAt IS NULL " +
           "  AND (owner.id = :userId " +
           "       OR EXISTS (SELECT 1 FROM BoardFeedback f " +
           "                  WHERE f.board = b AND f.user.id = :userId AND f.rating = :rating)) " +
           "ORDER BY b.likeCount DESC, b.id ASC")
    List<Board> findMyBoards(@Param("userId") Long userId,
                             @Param("rating") Rating rating,
                             Pageable pageable);

    /**
     * 이 사용자가 이미 같은 구성의 USER_CUSTOM 보드를 갖고 있는지.
     *
     * <p>uk_board_user_signature와 같은 규칙이다. DB 제약에 부딪혀 500이 나기 전에
     * 미리 확인해 사용자에게 설명 가능한 메시지를 돌려주기 위한 조회다.</p>
     */
    @Query("SELECT b FROM Board b " +
           "WHERE b.boardType = com.crates.crates.enumData.BoardType.USER_CUSTOM " +
           "AND b.user.id = :userId AND b.contentSignature = :signature AND b.deletedAt IS NULL")
    Optional<Board> findActiveUserBoardBySignature(@Param("userId") Long userId,
                                                   @Param("signature") String signature);

    // 엔티티를 읽어 +1 하고 저장하면 동시 요청에서 갱신 손실이 난다.
    // 증감을 DB에 맡겨 원자적으로 처리하고, Board에 setter를 열지 않는다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Board b SET b.likeCount = b.likeCount + 1 WHERE b.id = :boardId")
    int incrementLikeCount(@Param("boardId") Long boardId);

    // GREATEST로 하한을 두어 어떤 경우에도 음수가 되지 않게 한다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Board b SET b.likeCount = " +
           "CASE WHEN b.likeCount > 0 THEN b.likeCount - 1 ELSE 0 END WHERE b.id = :boardId")
    int decrementLikeCount(@Param("boardId") Long boardId);

    @Query("SELECT b.likeCount FROM Board b WHERE b.id = :boardId")
    Optional<Long> findLikeCountById(@Param("boardId") Long boardId);
}
