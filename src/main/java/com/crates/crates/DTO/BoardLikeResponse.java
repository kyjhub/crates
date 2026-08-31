package com.crates.crates.DTO;

/**
 * @param boardId   새로 저장된 보드라면 그 id. 프론트는 이 값을 캐시에 반영해야
 *                  같은 보드를 두 번 저장하지 않는다.
 * @param likeCount 반영 후의 좋아요 수
 * @param liked     요청한 사용자의 좋아요 여부
 */
public record BoardLikeResponse(
        Long boardId,
        Long likeCount,
        boolean liked
) {
}
