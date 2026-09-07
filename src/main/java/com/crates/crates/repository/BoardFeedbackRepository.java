package com.crates.crates.repository;

import com.crates.crates.DTO.LikedBoardDto;
import com.crates.crates.entity.board.BoardFeedback;
import com.crates.crates.enumData.Rating;
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
     */
    @Query("SELECT new com.crates.crates.DTO.LikedBoardDto(f.board.id, f.createdAt) " +
           "FROM BoardFeedback f " +
           "WHERE f.user.id = :userId AND f.rating = :rating AND f.board.deletedAt IS NULL")
    List<LikedBoardDto> findLikedBoardsWithTime(@Param("userId") Long userId,
                                                @Param("rating") Rating rating);

    /** 화면에 보이는 보드들 중 내가 좋아요한 것만 추려낸다. 보드마다 조회하지 않기 위한 일괄 조회. */
    @Query("SELECT f.board.id FROM BoardFeedback f " +
           "WHERE f.user.id = :userId AND f.rating = :rating AND f.board.id IN :boardIds")
    List<Long> findLikedBoardIds(@Param("userId") Long userId,
                                 @Param("rating") Rating rating,
                                 @Param("boardIds") List<Long> boardIds);
}
