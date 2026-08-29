package com.crates.crates.oauth;

import com.crates.crates.entity.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * 카카오·네이버처럼 openid 스코프가 없는 일반 OAuth2 제공자용 사용자 서비스.
 * 구글은 OIDC 흐름을 타므로 CustomOidcUserService가 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final OAuth2UserRegistrar oAuth2UserRegistrar;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException
    {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        try
        {
            String registrationId = userRequest.getClientRegistration().getRegistrationId();
            String userNameAttributeName = userRequest.getClientRegistration()
                    .getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();

            User user = oAuth2UserRegistrar.resolve(registrationId, oAuth2User.getAttributes());

            // 💡 어댑터 패턴에 입각한 CustomOAuth2User 반환
            return new CustomOAuth2User(user, oAuth2User.getAttributes(), userNameAttributeName);
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
}
