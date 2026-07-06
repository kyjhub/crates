package com.crates.crates.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiry}")
    private long accessExpiry;

    @Value("${jwt.refresh-expiry}")
    private long refreshExpiry;

    private SecretKey secretKey;

    @PostConstruct
    private void init() {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long userId) {
        return buildToken(userId, TokenType.ACCESS, accessExpiry, null);
    }

    public String createRefreshToken(Long userId, String jti) {
        return buildToken(userId, TokenType.REFRESH, refreshExpiry, jti);
    }

    private String buildToken(Long userId, TokenType type, long expiryMs, String jti) {
        Date now = new Date();
        JwtBuilder builder = Jwts.builder()
                .subject(userId.toString())
                .claim("typ", type.name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiryMs));

        if (jti != null) {
            builder.id(jti);
        }

        return builder.signWith(secretKey).compact();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("만료된 JWT: {}", e.getMessage());
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("유효하지 않은 JWT: {}", e.getMessage());
        }
        return false;
    }

    public Long getUserId(String token) {
        String subject = parseClaims(token)
                .getSubject();
        return Long.parseLong(subject);
    }

    public String getJti(String token) {
        return parseClaims(token).getId();
    }

    public TokenType getTokenType(String token) {
        String type = parseClaims(token).get("typ", String.class);
        if (type == null) {
            return null;
        }

        try {
            return TokenType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public LocalDateTime getExpirationAt(String token) {
        Date expiration = parseClaims(token).getExpiration();
        return LocalDateTime.ofInstant(expiration.toInstant(), ZoneId.systemDefault());
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String getJtiIgnoringExpiration(String token) {
        try {
            return parseClaims(token).getId();
        } catch (ExpiredJwtException e) {
            return e.getClaims().getId();   // 만료됐어도 서명은 검증된 상태이므로 jti 신뢰 가능
        }
        // MalformedJwtException / SignatureException / IllegalArgumentException 등은 그대로 propagate
    }
}
