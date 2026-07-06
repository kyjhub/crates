package com.crates.crates.controller;

import com.crates.crates.DTO.*;
import com.crates.crates.jwt.JwtTokenProvider;
import com.crates.crates.service.AuthService;
import com.crates.crates.user.CustomUserDetails;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;

    // 1. 로컬(직접) 회원가입
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<AccessTokenResponseDto>> signup(@Valid @RequestBody SignupRequestDto request, HttpServletResponse response)
    {
        TokenResponseDto tokenResponse = authService.signup(request);
        ResponseCookie cookie = buildRefreshTokenCookie(tokenResponse.getRefreshToken(), jwtTokenProvider.getRefreshExpirySeconds());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, new AccessTokenResponseDto(tokenResponse.getAccessToken()), "로컬 회원가입 성공!"));
    }

    // 2. 로컬(직접) 로그인
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AccessTokenResponseDto>> login(@Valid @RequestBody LoginRequestDto request, HttpServletResponse response)
    {
        TokenResponseDto tokenResponse = authService.login(request);
        ResponseCookie cookie = buildRefreshTokenCookie(tokenResponse.getRefreshToken(), jwtTokenProvider.getRefreshExpirySeconds());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, new AccessTokenResponseDto(tokenResponse.getAccessToken()), "로컬 로그인 성공!"));
    }

    // 3. OAuth 신규 가입 후 프로필 완성 (JWT 인증 필수)
    @PostMapping("/profile")
    public ResponseEntity<ApiResponse<Void>> completeProfile(
            @Valid @RequestBody ProfileRequestDto request,
            @AuthenticationPrincipal CustomUserDetails userDetails)
    {
         Long userId = userDetails.getUserId();

        authService.completeProfile(userId, request);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<Void>(true, null, "Profile Complete!"));
    }

    // Token 갱신
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AccessTokenResponseDto>> refresh(
            @CookieValue("refreshToken") String refreshToken,
            HttpServletResponse response)
    {
        TokenResponseDto tokenResponse = authService.refresh(refreshToken);
        ResponseCookie cookie = buildRefreshTokenCookie(tokenResponse.getRefreshToken(), jwtTokenProvider.getRefreshExpirySeconds());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, new AccessTokenResponseDto(tokenResponse.getAccessToken()), "token 갱신 성공!"));
    }

    @PostMapping("/oauth-token")
    public ResponseEntity<ApiResponse<AccessTokenResponseDto>> exchangeOAuthToken(
            @RequestBody OAuthTokenRequestDto request,
            HttpServletResponse response) {
        
        TokenResponseDto tokenResponse = authService.exchangeOAuthToken(request.tempToken());
        
        ResponseCookie cookie = buildRefreshTokenCookie(tokenResponse.getRefreshToken(), jwtTokenProvider.getRefreshExpirySeconds());
        
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, new AccessTokenResponseDto(tokenResponse.getAccessToken()), "OAuth 토큰 교환 성공!"));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue("refreshToken") String refreshToken,
            HttpServletResponse response) {
        authService.logout(refreshToken);
        ResponseCookie cookie = buildRefreshTokenCookie("", 0);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, null, "로그아웃 성공!"));
    }

    private ResponseCookie buildRefreshTokenCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from("refreshToken", value)
                .httpOnly(true)
                .secure(true)
                .path("/api/auth")
                .maxAge(maxAgeSeconds)
                .sameSite("Strict")
                .build();
    }
}
