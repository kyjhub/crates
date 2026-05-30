package com.crates.crates.oauth;

import com.crates.crates.jwt.JwtTokenProvider;
import com.crates.crates.service.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler
{

    private final RefreshTokenService refreshTokenService;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${app.oauth2.redirect-uri}")
    private String redirectUri;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException
    {

        CustomOAuth2User oauth2User = (CustomOAuth2User) authentication.getPrincipal();
        Long userId = oauth2User.getUserId();

        // PK는 항상 존재하므로 신규/기존 구분 없이 JWT 발급
        String accessToken = jwtTokenProvider.createToken(userId);
        String refreshToken = refreshTokenService.issue(userId);

        // 3. Refresh Token을 HttpOnly 쿠키에 담기
        Cookie refreshTokenCookie = new Cookie("refreshToken", refreshToken);
        refreshTokenCookie.setHttpOnly(true);      // 자바스크립트에서 접근 불가 (XSS 방어)
        refreshTokenCookie.setSecure(true);        // HTTPS 통신에서만 전송 (로컬 테스트 시에는 false로 하거나, HTTPS 설정 필요)
        refreshTokenCookie.setPath("/");           // 모든 경로에서 쿠키 전송
        refreshTokenCookie.setMaxAge(14 * 24 * 60 * 60); // 14일 (초 단위)

        response.addCookie(refreshTokenCookie);

        // 프론트가 nickname == null 여부로 프로필 완성 여부를 판단하여 라우팅
        String targetUrl = UriComponentsBuilder.fromUriString(redirectUri)
                .build().toUriString();

        log.info("소셜 로그인 완료. JWT 발급. userId: {}", oauth2User.getUserId());
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
