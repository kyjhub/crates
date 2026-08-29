package com.crates.crates.DTO;

import lombok.Builder;

@Builder
public record ContentResponseDto(
        Long id,
        String title,
        String imageUrl,
        String contentType, // DTYPE (Discriminator Value)
        // java.time.Year를 그대로 노출하면 Jackson이 문자열("2001")로 직렬화할 수 있어
        // 프론트에서 숫자로 다루기 어렵다. 응답 경계에서 연도 숫자로 변환한다.
        Integer releaseYear
) {
    public static ContentResponseDto of(ContentQueryDto query, String imageUrl)
    {
        return ContentResponseDto.builder()
                .id(query.id())
                .title(query.title())
                .imageUrl(imageUrl)
                .contentType(query.contentType())
                .releaseYear(query.releaseYear() == null ? null : query.releaseYear().getValue())
                .build();
    }
}
