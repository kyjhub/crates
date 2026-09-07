package com.crates.crates.service;

/**
 * 좋아요를 누르거나 취소했을 때 발행된다. 취향 벡터를 다시 계산하라는 신호다.
 *
 * <p>어떤 보드였는지는 담지 않는다. 재계산은 좋아요 집합 전체를 다시 읽어 계산하므로
 * 개별 보드 정보가 필요 없고, 담아두면 "이 보드만 반영하면 되나?"라는 오해를 부른다.</p>
 */
public record BoardLikeChangedEvent(Long userId) {
}
