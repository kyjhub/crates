package com.crates.crates.DTO;

import java.util.List;

/**
 * @param liked 조회한 사용자가 이 보드에 좋아요를 눌렀는지.
 *              likeCount는 board 테이블의 집계값이고, liked는 board_feedback에서 온
 *              사용자별 정보라 출처가 다르다.
 */
public record BoardWithContentsDto(
        Long boardId,
        String title,
        Long likeCount,
        boolean liked,
        List<ContentResponseDto> contents
) {

    /**
     * 아직 DB에 저장되지 않은 보드(검색어 유사 보드, 오늘의 추천 보드)의 응답.
     *
     * <p>boardId가 null이라는 것이 프론트에게 "좋아요를 누르면 그때 저장된다"는 신호다.
     * 이때는 {@code POST /api/boards/{boardId}/likes}가 아니라
     * {@code POST /api/boards/likes}로 title과 contentIds를 함께 보내야 한다.</p>
     */
    public static BoardWithContentsDto unsaved(String title, List<ContentResponseDto> contents)
    {
        return new BoardWithContentsDto(null, title, 0L, false, contents);
    }
}
