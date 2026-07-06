package com.crates.crates.service;

import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.UserRefreshToken;
import com.crates.crates.jwt.JwtTokenProvider;
import com.crates.crates.jwt.TokenType;
import io.jsonwebtoken.JwtException;
import com.crates.crates.repository.UserRefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefreshTokenService {

    private final UserRefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;

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
        String jti = UUID.randomUUID().toString();
        String refreshTokenJwt = jwtTokenProvider.createRefreshToken(userId, jti);

        UserRefreshToken refreshToken = UserRefreshToken.builder()
                .userId(userId)
                .jti(jti)
                .expiresAt(jwtTokenProvider.getExpirationAt(refreshTokenJwt))
                .build();

        // 4. 저장 후 반환
        refreshTokenRepository.save(refreshToken);
        return refreshTokenJwt;
    }

    @Transactional
    public RotateResult rotate(String refreshTokenJwt) {
        if (!jwtTokenProvider.validateToken(refreshTokenJwt)) {
            throw new BusinessException("유효하지 않거나 만료된 리프레시 토큰입니다.");
        }

        if (jwtTokenProvider.getTokenType(refreshTokenJwt) != TokenType.REFRESH) {
            throw new BusinessException("리프레시 토큰이 아닙니다.");
        }

        String jti = jwtTokenProvider.getJti(refreshTokenJwt);
        if (jti == null || jti.isBlank()) {
            throw new BusinessException("유효하지 않은 리프레시 토큰입니다.");
        }

        UserRefreshToken oldToken = refreshTokenRepository.findByJti(jti)
                .orElseThrow(() -> new BusinessException("유효하지 않은 리프레시 토큰입니다."));

        Long userId = jwtTokenProvider.getUserId(refreshTokenJwt);
        if (!oldToken.getUserId().equals(userId)) {
            throw new BusinessException("유효하지 않은 리프레시 토큰입니다.");
        }

        refreshTokenRepository.delete(oldToken);

        String newTokenValue = issue(userId);
        return new RotateResult(userId,  newTokenValue);
    }

    @Transactional
    public void deleteByToken(String refreshTokenJwt) {
        String jti;
        try {
            jti = jwtTokenProvider.getJtiIgnoringExpiration(refreshTokenJwt);
        } catch (JwtException | IllegalArgumentException e) {
            return;  // 위조/형식오류 토큰 - 삭제할 대상 없음, 조용히 종료
        }

        if (jti != null && !jti.isBlank()) {
            refreshTokenRepository.deleteByJti(jti);
        }
    }

    @Transactional
    public void deleteByUserId(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    @Transactional
    public void deleteExpiredTokens() {
        refreshTokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
    }
}
