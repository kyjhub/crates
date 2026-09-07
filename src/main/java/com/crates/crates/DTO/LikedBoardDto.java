package com.crates.crates.DTO;

import java.time.LocalDateTime;

/**
 * 사용자가 좋아요한 보드와 그 시각.
 *
 * <p>취향 벡터를 다시 계산할 때 쓴다. 좋아요한 <b>날짜</b>를 최신순으로 줄 세워 가중치를
 * 매기므로 시각까지 필요하다.</p>
 */
public record LikedBoardDto(Long boardId, LocalDateTime likedAt) {
}
