package com.crates.crates.DTO;

import java.util.List;

public record MovieResponseDTO(
        Integer runningTime,
        List<String> director,
        List<String> actor,
        String plot
) implements ContentDetailResponse {
}
