package com.crates.crates.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_refresh_tokens")
@Getter
@NoArgsConstructor
public class UserRefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true)
    private String tokenValue;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Builder
    public UserRefreshToken(Long userId, String tokenValue, LocalDateTime expiresAt) {
        this.userId = userId;
        this.tokenValue = tokenValue;
        this.expiresAt = expiresAt;
    }
}
