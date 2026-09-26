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

    // 로컬 로그인. 회원가입은 OAuth만 받는다 — 로컬 계정은 부하테스트용(load-test/seed.sh)으로만 만들어진다.
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AccessTokenResponseDto>> login(@Valid @RequestBody LoginRequestDto request, HttpServletResponse response)
    {
        TokenResponseDto tokenResponse = authService.login(request);
        ResponseCookie cookie = buildRefreshTokenCookie(tokenResponse.refreshToken(), jwtTokenProvider.getRefreshExpirySeconds());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, new AccessTokenResponseDto(tokenResponse.accessToken()), "로컬 로그인 성공!"));
    }

    // OAuth 신규 가입 후 프로필 완성 (JWT 인증 필수)
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
            @CookieValue(value = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response)
    {
        // 쿠키가 없는 상태(최초 방문, 로그아웃 이후)는 예외가 아니라 정상적인 비로그인 상황이다.
        // required=true로 두면 MissingRequestCookieException이 GlobalExceptionHandler까지 올라가
        // 프론트가 부팅할 때마다 500과 스택트레이스를 남긴다.
        if (refreshToken == null)
        {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiResponse<>(false, null, "인증이 필요합니다."));
        }

        TokenResponseDto tokenResponse = authService.refresh(refreshToken);
        ResponseCookie cookie = buildRefreshTokenCookie(tokenResponse.refreshToken(), jwtTokenProvider.getRefreshExpirySeconds());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, new AccessTokenResponseDto(tokenResponse.accessToken()), "token 갱신 성공!"));
    }

    @PostMapping("/oauth-token")
    public ResponseEntity<ApiResponse<AccessTokenResponseDto>> exchangeOAuthToken(
            @RequestBody OAuthTokenRequestDto request,
            HttpServletResponse response) {
        
        TokenResponseDto tokenResponse = authService.exchangeOAuthToken(request.tempToken());
        
        ResponseCookie cookie = buildRefreshTokenCookie(tokenResponse.refreshToken(), jwtTokenProvider.getRefreshExpirySeconds());
        
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, new AccessTokenResponseDto(tokenResponse.accessToken()), "OAuth 토큰 교환 성공!"));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(value = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response) {
        // 쿠키가 이미 없어도 로그아웃은 성공으로 처리한다. 클라이언트 입장에서 결과가 같아야 재시도가 안전하다.
        if (refreshToken != null)
        {
            authService.logout(refreshToken);
        }
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
