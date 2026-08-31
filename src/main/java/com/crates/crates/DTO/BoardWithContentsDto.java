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
}
