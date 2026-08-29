package com.crates.crates.DTO;

import lombok.Builder;

/**
 * JWT 반환용 공통 DTO
 */
@Builder
public record TokenResponseDto(String accessToken, String refreshToken) {
}
