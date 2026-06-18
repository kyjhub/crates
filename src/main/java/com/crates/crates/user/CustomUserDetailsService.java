package com.crates.crates.user;

import com.crates.crates.entity.user.User;
import com.crates.crates.enumData.LoginType;
import com.crates.crates.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String loginId) throws UsernameNotFoundException {
        User user = userRepository.findByLoginId(loginId)
                .orElseThrow(() -> new UsernameNotFoundException("존재하지 않는 회원 아이디입니다: " + loginId));

        if (user.getLoginType() == LoginType.OAUTH) {
            throw new BadCredentialsException("소셜 로그인 전용 계정입니다. 소셜 로그인을 통해 접속해 주세요.");
        }

        return new CustomUserDetails(user);
    }

    // JWT 필터에서 userId 기반으로 조회
    public UserDetails loadUserById(Long userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UsernameNotFoundException("존재하지 않는 회원입니다: " + userId));
        return new CustomUserDetails(user);
    }
}
