package com.crates.crates.oauth;

import com.crates.crates.entity.user.User;
import com.crates.crates.enumData.AuthProvider;
import com.crates.crates.enumData.LoginType;
import com.crates.crates.enumData.Role;
import com.crates.crates.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 소셜 원본 속성으로 사용자를 조회하거나 신규 가입시키는 공통 로직.
 * OAuth2(카카오·네이버)와 OIDC(구글) 두 경로가 같은 규칙을 쓰도록 한곳에 모은다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2UserRegistrar {

    private final UserRepository userRepository;

    @Transactional
    public User resolve(String registrationId, Map<String, Object> attributes)
    {
        OAuth2UserInfo oauth2UserInfo = OAuth2UserInfoFactory.getOAuth2UserInfo(registrationId, attributes);

        if (oauth2UserInfo.getProviderId() == null || oauth2UserInfo.getProviderId().isBlank())
        {
            throw new OAuth2AuthenticationException("소셜 플랫폼으로부터 고유 식별값을 획득할 수 없습니다.");
        }

        AuthProvider authProvider = AuthProvider.valueOf(registrationId.toUpperCase());

        // (provider, providerId) 복합 유니크 제약조건 기반 유저 검색
        return userRepository.findByProviderAndProviderId(authProvider, oauth2UserInfo.getProviderId())
                .map(user ->
                {
                    log.info("기존 소셜 계정 로그인 성공. ID: {}, 제공자: {}", user.getId(), user.getProvider());
                    return user;
                })
                .orElseGet(() -> registerNewUser(authProvider, oauth2UserInfo));
    }

    private User registerNewUser(AuthProvider provider, OAuth2UserInfo oauth2UserInfo)
    {
        // 💡 loginType을 OAUTH로 격리하여 폼 로그인 오용을 차단합니다.
        User user = User.builder()
                .provider(provider)
                .providerId(oauth2UserInfo.getProviderId())
                .loginType(LoginType.OAUTH)
                .role(Role.USER)
                .build();

        // 취향 벡터는 여기서 만들지 않는다. 가입 직후 화면에서 사용자가 콘텐츠를 고르면
        // 그 평균으로 만든다(UserVectorService.initialize).
        User savedUser = userRepository.save(user);

        log.info("신규 소셜 계정 가입 완료. 가입 경로: {}", savedUser.getProvider());
        return savedUser;
    }
}
