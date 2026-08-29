package com.crates.crates.DTO;

public record BookResponseDTO(String author, String publisher, String plot) implements ContentDetailResponse {
}
