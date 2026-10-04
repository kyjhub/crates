package com.crates.crates.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "user_refresh_tokens",
        // 로그인(사용자 토큰을 만료순으로 잠금 조회)과 로그아웃(사용자 토큰 삭제)이 user_id로 찾는다. 근거는 V2.
        indexes = @Index(name = "idx_user_refresh_tokens_user", columnList = "userId, expiresAt")
)
@Getter
@NoArgsConstructor
public class UserRefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 36)
    private String jti;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Builder
    public UserRefreshToken(Long userId, String jti, LocalDateTime expiresAt) {
        this.userId = userId;
        this.jti = jti;
        this.expiresAt = expiresAt;
    }
}
