package com.crates.crates.oauth;

import com.crates.crates.entity.user.User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.util.List;

/**
 * 구글처럼 openid 스코프를 쓰는 OIDC 제공자용 principal.
 * CustomOAuth2User와 같은 도메인 계약을 만족시키되, ID 토큰을 다루는 DefaultOidcUser를 상속한다.
 */
public class CustomOidcUser extends DefaultOidcUser implements OAuth2UserPrincipal {

    private final User user;   // 💡 도메인 엔티티 캡슐화

    public CustomOidcUser(User user, OidcIdToken idToken, OidcUserInfo userInfo, String nameAttributeKey)
    {
        // 권한은 소셜이 준 스코프가 아니라 우리 도메인의 Role에서 뽑는다. CustomOAuth2User와 동일한 규칙.
        super(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())), idToken, userInfo, nameAttributeKey);
        this.user = user;
    }

    @Override
    public User getUser()
    {
        return user;
    }

    @Override
    public Long getUserId()
    {
        return user.getId();
    }
}
