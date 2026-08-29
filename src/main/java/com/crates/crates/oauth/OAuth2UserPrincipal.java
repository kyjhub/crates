package com.crates.crates.oauth;

import com.crates.crates.entity.user.User;

/**
 * 소셜 로그인 결과 principal이 공통으로 노출하는 도메인 식별 계약.
 *
 * 구글은 openid 스코프를 포함하는 OIDC 제공자라 스프링이 OidcUser 계열 객체를 만들고,
 * 카카오·네이버는 일반 OAuth2라 OAuth2User 계열 객체를 만든다. 두 계층은 상속 관계가 없어
 * 성공 핸들러가 한쪽 구현체로 캐스팅하면 다른 쪽에서 ClassCastException이 난다.
 * 그래서 캐스팅 대상을 구현체가 아니라 이 인터페이스로 둔다.
 */
public interface OAuth2UserPrincipal {

    User getUser();

    Long getUserId();
}
