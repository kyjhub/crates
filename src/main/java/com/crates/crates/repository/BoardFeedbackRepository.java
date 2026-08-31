package com.crates.crates.repository;

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

    /** 화면에 보이는 보드들 중 내가 좋아요한 것만 추려낸다. 보드마다 조회하지 않기 위한 일괄 조회. */
    @Query("SELECT f.board.id FROM BoardFeedback f " +
           "WHERE f.user.id = :userId AND f.rating = :rating AND f.board.id IN :boardIds")
    List<Long> findLikedBoardIds(@Param("userId") Long userId,
                                 @Param("rating") Rating rating,
                                 @Param("boardIds") List<Long> boardIds);
}
