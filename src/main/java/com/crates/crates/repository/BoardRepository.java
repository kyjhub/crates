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
     * "내 보드"(보관함 전체 탭) — 내가 만든 보드와 내가 좋아요한 보드를 합쳐 최근에 담은 순으로.
     *
     * <p>이 서비스에서 좋아요는 사실상 "내 크레이트에 담기"다. 추천·검색 보드는 좋아요를 누른
     * 시점에 저장되고, 그걸 고치면 내 USER_CUSTOM 보드가 된다. 두 경로로 모인 보드가 한 곳에
     * 보여야 사용자가 "내가 모은 것"을 한눈에 본다.</p>
     *
     * <p>공개 여부는 걸지 않는다. 내 PRIVATE 보드는 나에게는 보여야 하고, 남의 PRIVATE 보드는
     * 애초에 좋아요할 수 없으니 여기 들어올 수 없다.</p>
     *
     * <p><b>정렬이 좋아요 수가 아니라 "담은 시각"인 이유가 두 가지다.</b> 첫째, 좋아요 수 정렬은
     * 인기 보드의 논리다. 내 보관함에서는 최근에 담은 것이 위에 와야 한다. 둘째, like_count는
     * 다른 사용자들의 좋아요로 계속 변해서 페이징이 깨진다 — 2페이지를 불러오는 사이에 1페이지의
     * 보드가 아래로 밀리면 같은 보드가 두 번 보이고 다른 보드는 건너뛰어진다. 과거 시각은 변하지
     * 않으므로 무한 스크롤에서도 경계가 흔들리지 않는다.</p>
     *
     * <p>COALESCE는 "내 보관함에 들어온 시각"을 뜻한다. 좋아요한 보드면 좋아요를 누른 시각,
     * 내가 만든 보드면 생성 시각이다. 둘 다인 보드는 좋아요 시각을 쓴다. 두 값 모두 항상
     * 채워지므로(saveFeedback, newUserBoard, likeNewBoard) null이 새지 않는다.</p>
     *
     * <p>좋아요를 <b>LEFT JOIN</b>으로 바꾼 이유는 정렬 키(f.createdAt)를 꺼내야 해서다.
     * EXISTS 서브쿼리로는 조건 판정만 되고 값을 가져올 수 없다. uk_board_feedback_board_user가
     * (board_id, user_id) 유니크를 보장하므로 조인 결과는 보드당 최대 1행이고, 따라서 행이
     * 불어나지 않아 DISTINCT가 필요 없다. (이 제약이 없었다면 페이징 개수가 통째로 틀어진다.)</p>
     *
     * <p>소유자 조인을 <b>명시적 LEFT JOIN</b>으로 쓴다. b.user.id로 적으면 구현체에 따라
     * 암묵적 INNER JOIN이 되어, 소유자가 없는 전역 공용 보드(AI_RECOMMEND)가
     * 좋아요 조건을 만족해도 통째로 빠질 수 있다. 에러가 아니라 "일부가 안 보이는" 증상으로만
     * 드러나므로 애매하게 두지 않는다.</p>
     */
    /**
     * "내 보드"(보관함 전체 탭) — 내가 만든 보드와 내가 좋아요한 보드를 합쳐 최근에 담은 순으로.
     *
     * <p>원래는 LEFT JOIN 하나에 COALESCE 정렬로 적혀 있었다. 결과는 같고 비용의 차수가 다르다.
     *
     * <p><b>왜 바꿨나.</b> 위 JPQL은 20행을 돌려주려고 보드 전부와 내 좋아요 전부를 메모리에
     * 올린 뒤 정렬했다. 계정당 좋아요 1만 건 · 보드 2만 개에서 호출마다 10,841블록이었다.
     * 원인은 둘 다 "정렬을 인덱스로 대신할 수 없다"로 모인다.</p>
     *
     * <ul>
     *   <li>{@code ORDER BY COALESCE(f.createdAt, b.createdAt)} — LEFT JOIN 결과에 대한
     *       계산식이라 어떤 인덱스도 이 순서를 미리 갖고 있지 않다.</li>
     *   <li>{@code (owner.id = :userId OR f.id IS NOT NULL)} — 두 테이블에 걸친 OR라
     *       인덱스로 후보를 좁히지 못한다. 한 사람 것을 확인하려고 users를 통째로 훑었다.</li>
     * </ul>
     *
     * <p><b>COALESCE가 사실은 분기다.</b> 좋아요 행이 있으면 누른 시각, 없으면 만든 시각을 쓴다.
     * 그러면 "좋아요한 보드"와 "내가 만들었고 좋아요하지 않은 보드" 두 집합으로 나뉘고,
     * 각 집합은 단일 테이블 기준으로 정렬되므로 인덱스가 순서를 그대로 제공한다.
     * 두 갈래가 이미 정렬돼 있으니 DB는 Merge Append로 앞에서부터 필요한 만큼만 합친다.</p>
     *
     * <p><b>겹치는 보드는 좋아요 갈래가 갖는다.</b> 내가 만들고 내가 좋아요도 한 보드는 두 번
     * 나오면 안 된다. 만든 것 갈래에 {@code NOT EXISTS}를 걸어 배제한다 — COALESCE가 좋아요
     * 시각을 우선하던 것과 정확히 같은 규칙이다.</p>
     *
     * <p><b>각 갈래가 {@code cap}(= offset + size)만큼 가져와야 한다.</b> size만 가져오면
     * 2페이지부터 틀어진다. 한 갈래가 앞쪽을 독차지한 경우 뒤 페이지에 나올 행이 그 갈래의
     * 상위 size 안에 없을 수 있기 때문이다. 합친 뒤 바깥에서 다시 자른다.</p>
     *
     * <p>삭제된 보드는 조인이 아니라 {@code NOT EXISTS}로 배제한다. 살아있는 보드가 사실상
     * 전부라 조인하면 비용이 O(전체 보드)가 되지만, 삭제된 보드는 극소수라 배제하면
     * O(삭제된 보드)가 된다. idx_board_deleted(V3)가 받친다 — findLikedBoardsWithTime과 같은 이유다.</p>
     *
     * <p>동률 기준은 {@code b.id DESC}로 원본을 그대로 둔다. 좋아요 갈래의 인덱스 순서는
     * (created_at DESC, f.id DESC)라 b.id와 어긋나지만, PostgreSQL의 Incremental Sort가
     * created_at까지는 인덱스 순서를 믿고 같은 시각끼리만 다시 정렬한다. 덕분에 정확한 순서를
     * 지키면서도 앞에서 몇 건만 읽고 멈출 수 있다.</p>
     *
     * <p>네이티브인 이유는 JPQL에 집합 연산(UNION ALL)이 없어서다.</p>
     *
     * <p>실측(계정 1,001 / 보드 20,010 / 좋아요 1,000만 / 내가 만든 보드 2,000):</p>
     * <pre>
     *   기존   Hash Left Join → 3만 행을 만들어 1만 행 버림 → top-N 정렬   10,841블록
     *   이 쿼리 Merge Append → 좋아요 갈래는 21행만 읽고 멈춤                 187블록
     * </pre>
     */
    @Query(value = """
            SELECT b.*
              FROM board b
              JOIN ( (SELECT f.board_id AS bid, f.created_at AS sort_at
                        FROM board_feedback f
                       WHERE f.user_id = :userId AND f.rating = :rating
                         AND NOT EXISTS (SELECT 1 FROM board d
                                          WHERE d.id = f.board_id AND d.deleted_at IS NOT NULL)
                       ORDER BY f.created_at DESC, f.board_id DESC
                       LIMIT :cap)
                     UNION ALL
                     (SELECT o.id AS bid, o.created_at AS sort_at
                        FROM board o
                       WHERE o.user_id = :userId AND o.deleted_at IS NULL
                         AND NOT EXISTS (SELECT 1 FROM board_feedback f2
                                          WHERE f2.board_id = o.id
                                            AND f2.user_id = :userId AND f2.rating = :rating)
                       ORDER BY o.created_at DESC, o.id DESC
                       LIMIT :cap)
                   ) picked ON picked.bid = b.id
             ORDER BY picked.sort_at DESC, picked.bid DESC
             LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<Board> findMyBoards(@Param("userId") Long userId,
                             @Param("rating") String rating,
                             @Param("size") int size,
                             @Param("offset") long offset,
                             @Param("cap") long cap);

    /**
     * 보관함 "만든 것" 탭 — 내가 만든 보드만.
     *
     * <p>ck_board_owner가 {@code (board_type = 'USER_CUSTOM') = (user_id IS NOT NULL)}을
     * 강제하므로 두 조건 중 하나는 사실 잉여다. 그래도 둘 다 적는다. 소유자가 나인 것과
     * 내가 만든 것이 같다는 사실은 제약을 알아야 보이는 것이라, 쿼리만 읽는 사람에게도
     * 의도가 드러나야 한다.</p>
     *
     * <p>여기는 좋아요가 걸리지 않으므로 board.created_at 하나로 정렬이 끝난다.
     * idx_board_owner (user_id, board_type)가 후보를 좁혀준다.</p>
     */
    @Query("SELECT b FROM Board b " +
           "WHERE b.user.id = :userId " +
           "  AND b.boardType = com.crates.crates.enumData.BoardType.USER_CUSTOM " +
           "  AND b.deletedAt IS NULL " +
           "ORDER BY b.createdAt DESC, b.id DESC")
    List<Board> findCreatedByMe(@Param("userId") Long userId, Pageable pageable);

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
