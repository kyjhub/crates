package com.crates.crates.service;

import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.UserRefreshToken;
import com.crates.crates.repository.UserRefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefreshTokenService {

    private final UserRefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.refresh-expiry}")
    private long refreshExpiryMs;

    public record RotateResult(Long userId, String newTokenValue) {}

    @Transactional
    public String issue(Long userId) {
        // 1. 기존 토큰 조회
        List<UserRefreshToken> tokens = refreshTokenRepository.findByUserIdOrderByExpiresAtAsc(userId);

        // 2. 최대 기기 수(3개) 유지 로직
        if (tokens.size() >= 3) {
            // 가장 오래된(만료일이 가장 빠른) 토큰 삭제
            refreshTokenRepository.delete(tokens.get(0));
        }

        // 3. 새 리프레시 토큰 발급
        String newTokenValue = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plus(refreshExpiryMs, ChronoUnit.MILLIS);

        UserRefreshToken refreshToken = UserRefreshToken.builder()
                .userId(userId)
                .tokenValue(newTokenValue)
                .expiresAt(expiresAt)
                .build();

        // 4. 저장 후 반환
        refreshTokenRepository.save(refreshToken);
        return newTokenValue;
    }

    @Transactional
    public RotateResult rotate(String tokenValue) {
        // 1. 토큰 조회
        UserRefreshToken oldToken = refreshTokenRepository.findByTokenValue(tokenValue)
                .orElseThrow(() -> new BusinessException("유효하지 않은 리프레시 토큰입니다."));

        // 2. 만료 여부 확인
        if (oldToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            refreshTokenRepository.delete(oldToken);
            throw new BusinessException("리프레시 토큰이 만료되었습니다. 다시 로그인해주세요.");
        }

        // 3. 기존 토큰 정보 저장 및 삭제 (RTR 방식)
        Long userId = oldToken.getUserId();
        refreshTokenRepository.delete(oldToken);

        // 4. 새 토큰 발급
        String newTokenValue = issue(userId);
        return new RotateResult(userId,  newTokenValue);
    }

    @Transactional
    public void deleteByToken(String tokenValue) {
        refreshTokenRepository.deleteByTokenValue(tokenValue);
    }

    @Transactional
    public void deleteByUserId(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }
}