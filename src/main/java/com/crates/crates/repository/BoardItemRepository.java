package com.crates.crates.repository;

import com.crates.crates.DTO.BoardContentIdDto;
import com.crates.crates.entity.board.BoardItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BoardItemRepository extends JpaRepository<BoardItem, Long> {
    // JPQL을 사용해 딱 'content'만 프로젝션(추출)해서 리스트로 반환.
    // slotNo 정렬이 없으면 DB가 주는 순서로 나와서 사용자가 배치한 순서가 화면에 반영되지 않는다.
    @Query("SELECT bi.content.id FROM BoardItem bi WHERE bi.board.id = :boardId ORDER BY bi.slotNo")
    List<Long> findContentIdsByBoardId(@Param("boardId") Long boardId);

    // 보드별로 묶어 쓰기 때문에 board.id로 먼저 정렬한 뒤 슬롯 순서를 맞춘다.
    @Query("SELECT new com.crates.crates.DTO.BoardContentIdDto(bi.board.id, bi.content.id) " +
            "FROM BoardItem bi WHERE bi.board.id IN :boardIds ORDER BY bi.board.id, bi.slotNo")
    List<BoardContentIdDto> findContentIdsByBoardIds(@Param("boardIds") List<Long> boardIds);
}
