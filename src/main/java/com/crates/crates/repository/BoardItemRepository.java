package com.crates.crates.repository;

import com.crates.crates.entity.Board.BoardItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BoardItemRepository extends JpaRepository<BoardItem, Long> {
    // JPQL을 사용해 딱 'content'만 프로젝션(추출)해서 리스트로 반환
    @Query("SELECT bi.content.id FROM BoardItem bi WHERE bi.board.id = :boardId")
    List<Long> findContentIdsByBoardId(@Param("boardId") Long boardId);
}
