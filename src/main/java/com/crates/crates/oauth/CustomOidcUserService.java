package com.crates.crates.oauth;

import com.crates.crates.entity.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * 구글(OIDC) 로그인 전용 사용자 서비스.
 *
 * userInfoEndpoint().userService(...)는 일반 OAuth2 흐름에만 적용되기 때문에,
 * openid 스코프가 붙는 구글은 이 oidcUserService를 따로 등록하지 않으면
 * 스프링 기본 구현이 DefaultOidcUser를 만들어버린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOidcUserService extends OidcUserService {

    private final OAuth2UserRegistrar oAuth2UserRegistrar;

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException
    {
        OidcUser oidcUser = super.loadUser(userRequest);

        try
        {
            String registrationId = userRequest.getClientRegistration().getRegistrationId();
            User user = oAuth2UserRegistrar.resolve(registrationId, oidcUser.getAttributes());

            return new CustomOidcUser(user, oidcUser.getIdToken(), oidcUser.getUserInfo(), resolveNameAttributeKey(userRequest, oidcUser));
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

    /**
     * DefaultOidcUser는 nameAttributeKey에 해당하는 클레임이 없으면 생성 자체가 실패한다.
     * 설정값이 비었거나 클레임에 없으면 OIDC 표준 식별자인 sub로 되돌린다.
     */
    private String resolveNameAttributeKey(OidcUserRequest userRequest, OidcUser oidcUser)
    {
        String nameAttributeKey = userRequest.getClientRegistration()
                .getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();

        if (nameAttributeKey == null || !oidcUser.getAttributes().containsKey(nameAttributeKey))
        {
            return IdTokenClaimNames.SUB;
        }

        return nameAttributeKey;
    }
}
