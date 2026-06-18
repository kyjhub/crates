package com.crates.crates.user;

import com.crates.crates.entity.user.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Getter
public class CustomUserDetails implements UserDetails {

    private final User user; // 💡 도메인 엔티티 캡슐화 (Adapter Pattern)

    public CustomUserDetails(User user) {
        this.user = user;
    }

    @Override
    public String getUsername() {
        // 닉네임(고유) 또는 이메일로 반환하는게 좋을듯
        return user.getLoginId() != null ? user.getLoginId() : user.getEmail();
    }

    @Override
    public String getPassword() {
        return user.getPwd(); // 암호화된 패스워드 반환
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // ROLE Enum 가공하여 부여
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    // 💡 도메인 엔티티의 계정 상태 값을 안전하게 위임 가능
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return user.getDeletedAt() == null; }

    // 비즈니스 편의를 위한 식별 ID 반환 메서드
    public Long getUserId() {
        return user.getId();
    }
}