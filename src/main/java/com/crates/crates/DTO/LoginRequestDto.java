package com.crates.crates.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

/**
 * 직접 로그인 시
 */
@Builder
public record LoginRequestDto(
        @NotBlank(message = "아이디를 입력해주세요.") String loginId,
        @NotBlank(message = "비밀번호를 입력해주세요.") String pwd
) {
}
