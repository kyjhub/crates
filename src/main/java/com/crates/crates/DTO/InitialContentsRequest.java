package com.crates.crates.DTO;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 가입 직후 고른 취향 콘텐츠. 이 콘텐츠들의 평균이 가입 벡터가 된다.
 * 개수는 목록 길이로 알 수 있어 따로 받지 않는다.
 */
public record InitialContentsRequest(
        @NotNull(message = "콘텐츠를 골라주세요.")
        @Size(min = 1, max = 10, message = "콘텐츠는 1개 이상 10개 이하로 골라주세요.")
        List<@NotNull(message = "콘텐츠 id가 비어 있습니다.") Long> contentIds
) {
}
