package com.crates.crates.oauth;

import com.crates.crates.service.OAuthTempTokenService;
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

    private final OAuthTempTokenService oAuthTempTokenService;

    @Value("${app.oauth2.redirect-uri}")
    private String redirectUri;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException
    {

        // 구글(OIDC)은 CustomOidcUser, 카카오·네이버(OAuth2)는 CustomOAuth2User가 넘어온다.
        // 두 타입의 공통 계약으로 받아야 제공자에 따라 ClassCastException이 나지 않는다.
        OAuth2UserPrincipal principal = (OAuth2UserPrincipal) authentication.getPrincipal();
        Long userId = principal.getUserId();

        // 임시 토큰 발급 및 Redis 저장
        String tempToken = oAuthTempTokenService.issue(userId);

        // 프론트엔드로 리다이렉트 (임시 토큰 포함)
        String targetUrl = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("tempToken", tempToken)
                .build().toUriString();

        log.info("소셜 로그인 완료. 임시 토큰 발급. userId: {}", userId);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
