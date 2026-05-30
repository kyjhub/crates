package com.crates.crates.oauth;

import com.crates.crates.enumData.AuthProvider;

import java.util.Map;

public class OAuth2UserInfoFactory {

    /**
     * @param registrationId google, kakao, naver 등의 소셜 식별자
     * @param attributes 소셜 로그인 성공 후 획득한 Raw 사용자 데이터
     * @return 규격화된 OAuth2UserInfo 추상화 객체
     */
    public static OAuth2UserInfo getOAuth2UserInfo(String registrationId, Map<String, Object> attributes) {
        if (AuthProvider.GOOGLE.name().equalsIgnoreCase(registrationId))
        {
            return new GoogleOAuth2UserInfo(attributes);
        }
        else if (AuthProvider.KAKAO.name().equalsIgnoreCase(registrationId))
        {
            return new KakaoOAuth2UserInfo(attributes);
        }
        else if (AuthProvider.NAVER.name().equalsIgnoreCase(registrationId))
        {
            return new NaverOAuth2UserInfo(attributes);
        }
        else
        {
            throw new IllegalArgumentException(
                    "지원하지 않는 소셜 로그인 제공자입니다: [" + registrationId + "]"
            );
        }
    }
}
