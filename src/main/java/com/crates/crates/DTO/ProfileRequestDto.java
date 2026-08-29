package com.crates.crates.DTO;

import com.crates.crates.enumData.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.LocalDate;

/**
 * OAuth 신규 유저가 프로필 완성 시
 */
@Builder
public record ProfileRequestDto(
        @NotBlank(message = "이메일을 입력해주세요.") @Email(message = "이메일 형식이 올바르지 않습니다.") String email,
        @NotBlank(message = "닉네임을 입력해주세요.") String nickname,
        @NotNull(message = "성별을 선택해주세요.") Gender gender,
        @NotNull(message = "생년월일을 입력해주세요.") LocalDate birthYear
) {
}
