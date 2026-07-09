package com.crates.crates.DTO;

import java.util.List;

public record BoardWithContentsDto(
        Long boardId,
        String title,
        Long likeCount,
        List<ContentResponseDto> contents
) {
}
