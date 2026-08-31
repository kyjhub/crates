package com.crates.crates.repository;

import com.crates.crates.entity.board.Board;
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
    List<Board> findByDeletedAtIsNullOrderByLikeCountDesc(Pageable pageable);

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
