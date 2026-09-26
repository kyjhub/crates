package com.crates.crates.DTO;

/**
 * 가입 후 단계가 끝났는지. 프론트는 false면 콘텐츠 선택 화면으로 보낸다.
 *
 * @param initialContentsSelected 취향 콘텐츠를 골라 취향 벡터가 만들어졌는지
 */
public record OnboardingStatusResponse(boolean initialContentsSelected) {
}
