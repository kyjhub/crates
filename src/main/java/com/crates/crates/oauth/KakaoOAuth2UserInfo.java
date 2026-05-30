package com.crates.crates.oauth;

import java.util.Map;

public class KakaoOAuth2UserInfo extends OAuth2UserInfo {

    public KakaoOAuth2UserInfo(Map<String, Object> attributes)
    {
        super(attributes);
    }

    @Override
    public String getProviderId()
    {
        // 카카오의 id값은 보통 Long/Integer 타입으로 내려오므로 안전하게 문자열 형변환 처리
        Object id = attributes.get("id");
        return id != null ? id.toString() : null;
    }
}
