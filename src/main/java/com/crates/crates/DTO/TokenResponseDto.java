package com.crates.crates.DTO;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * JWT 반환용 공통 DTO
 */
@Getter
@AllArgsConstructor
public class TokenResponseDto {
    private String accessToken;
    private String refreshToken;
}
