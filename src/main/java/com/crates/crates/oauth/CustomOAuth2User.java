package com.crates.crates.oauth;

import com.crates.crates.entity.user.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Getter
public class CustomOAuth2User implements OAuth2User, OAuth2UserPrincipal {

    private final User user;                        // 💡 도메인 엔티티 캡슐화
    private final Map<String, Object> attributes;   // 소셜 원본 데이터
    private final String nameAttributeKey;          // 구글의 "sub", 카카오의 "id" 등

    public CustomOAuth2User(User user, Map<String, Object> attributes, String nameAttributeKey) {
        this.user = user;
        this.attributes = attributes;
        this.nameAttributeKey = nameAttributeKey;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public String getName() {
        Object value = attributes.get(nameAttributeKey);
        return value != null ? value.toString() : "";
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    // 비즈니스 편의를 위한 식별 ID 반환 메서드
    @Override
    public Long getUserId() {
        return user.getId();
    }
}
