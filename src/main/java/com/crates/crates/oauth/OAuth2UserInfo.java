package com.crates.crates.oauth;

import java.util.Map;

public abstract class OAuth2UserInfo {

    // 💡 각 소셜 플랫폼이 반환한 Raw Attribute Map 객체
    protected Map<String, Object> attributes;

    public OAuth2UserInfo(Map<String, Object> attributes)
    {
        this.attributes = attributes;
    }

    public Map<String, Object> getAttributes()
    {
        return attributes;
    }

    // 💡 하위 구체적인 구현체 클래스들이 정의할 가상 메서드
    public abstract String getProviderId(); // 소셜의 고유 회원 번호
}

