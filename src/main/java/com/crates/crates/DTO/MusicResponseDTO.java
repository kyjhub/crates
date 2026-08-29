package com.crates.crates.DTO;

import java.util.List;

public record MusicResponseDTO(List<String> artist, String plot) implements ContentDetailResponse {
}
