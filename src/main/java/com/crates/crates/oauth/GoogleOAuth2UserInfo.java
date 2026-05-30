package com.crates.crates.oauth;

import java.util.Map;

public class GoogleOAuth2UserInfo extends OAuth2UserInfo {

    public GoogleOAuth2UserInfo(Map<String, Object> attributes)
    {
        super(attributes);
    }
    @Override
    public String getProviderId()
    {
        Object id = attributes.get("sub"); // 구글의 사용자 식별 고유키
        return id != null ? id.toString() : null;
    }
}
