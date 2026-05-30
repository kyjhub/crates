package com.crates.crates.oauth;

import com.crates.crates.entity.user.User;
import com.crates.crates.enumData.AuthProvider;
import com.crates.crates.enumData.LoginType;
import com.crates.crates.enumData.ROLE;
import com.crates.crates.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException
    {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        try
        {
            return processOAuth2User(userRequest, oAuth2User);
        }
        catch (AuthenticationException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new InternalAuthenticationServiceException(ex.getMessage(), ex);
        }
    }

    private OAuth2User processOAuth2User(OAuth2UserRequest userRequest, OAuth2User oAuth2User)
    {
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        String userNameAttributeName = userRequest.getClientRegistration()
                .getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();

        OAuth2UserInfo oauth2UserInfo = OAuth2UserInfoFactory.getOAuth2UserInfo(registrationId, oAuth2User.getAttributes());

        if (oauth2UserInfo.getProviderId() == null || oauth2UserInfo.getProviderId().isBlank())
        {
            throw new OAuth2AuthenticationException("소셜 플랫폼으로부터 고유 식별값을 획득할 수 없습니다.");
        }

        AuthProvider authProvider = AuthProvider.valueOf(registrationId.toUpperCase());

        // (provider, providerId) 복합 유니크 제약조건 기반 유저 검색
        Optional<User> userOptional = userRepository.findByProviderAndProviderId(authProvider, oauth2UserInfo.getProviderId());
        User user;

        if (userOptional.isPresent())
        {
            user = userOptional.get();
            log.info("기존 소셜 계정 로그인 성공. ID: {}, 제공자: {}", user.getId(), user.getProvider());
        }
        else
        {
            // 💡 존재하지 않는 신규 유저는 100% 빌더 패턴으로 생성 및 가입
            user = registerNewUser(authProvider, oauth2UserInfo);
        }

        // 💡 어댑터 패턴에 입각한 CustomOAuth2User 반환
        return new CustomOAuth2User(user, oAuth2User.getAttributes(), userNameAttributeName);
    }

    private User registerNewUser(AuthProvider provider, OAuth2UserInfo oauth2UserInfo)
    {

        // 💡 [클린 빌더 패턴 적용]
        // 💡 loginType을 OAUTH로 격리하여 폼 로그인 오용을 차단합니다.
        User user = User.builder()
                .provider(provider)
                .providerId(oauth2UserInfo.getProviderId())
                .loginType(LoginType.OAUTH) // 💡 LoginType.OAUTH 설정!
                .role(ROLE.USER)
                .build();

        log.info("신규 소셜 계정 가입 완료. 가입 경로: {}", user.getProvider());
        return userRepository.save(user);
    }
}
