package com.crates.crates.DTO;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 아직 DB에 없는 보드("오늘의 추천 보드", 검색어 유사 보드)에 좋아요를 누를 때 쓰는 요청.
 * 이 보드들은 사용자에게 보여줄 때 생성만 되고 저장되지 않으므로,
 * 좋아요 시점에 프론트가 제목과 콘텐츠 구성을 그대로 넘겨줘야 한다.
 */
public record BoardLikeRequest(
        @NotBlank(message = "보드 제목이 필요합니다.")
        String title,

        @NotEmpty(message = "보드에 담긴 콘텐츠가 필요합니다.")
        List<Long> contentIds
) {
}
