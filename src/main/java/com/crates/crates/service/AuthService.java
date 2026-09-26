package com.crates.crates.service;

import com.crates.crates.DTO.LoginRequestDto;
import com.crates.crates.DTO.ProfileRequestDto;
import com.crates.crates.DTO.TokenResponseDto;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.user.User;
import com.crates.crates.jwt.JwtTokenProvider;
import com.crates.crates.repository.UserRepository;
import com.crates.crates.user.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;

     private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final OAuthTempTokenService oAuthTempTokenService;

    @Transactional
    public TokenResponseDto login(LoginRequestDto request)
    {
        // 1. AuthenticationManager 위임
        // CustomUserDetailsService가 동작하여 유저 유무, LoginType.OAUTH 여부, 비밀번호 검증을 모두 수행합니다.
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.loginId(),
                        request.pwd()
                )
        );

        // 2. 검증 통과 후 JWT 발급
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();

        Long userId = userDetails.getUserId();

        String accessToken = jwtTokenProvider.createAccessToken(userId);
        String refreshToken = refreshTokenService.issue(userId);

        return TokenResponseDto.builder().accessToken(accessToken).refreshToken(refreshToken).build();
    }

    // Refresh Token 갱신
    @Transactional
    public TokenResponseDto refresh(String oldRefreshToken) {

        // 1. Refresh Token 로테이션 및 유저 ID 획득 로직
        RefreshTokenService.RotateResult result = refreshTokenService.rotate(oldRefreshToken);

        // String newAccessToken = jwtProvider.generateAccessToken(userId, "USER");
        String newAccessToken = jwtTokenProvider.createAccessToken(result.userId());

        return TokenResponseDto.builder().accessToken(newAccessToken).refreshToken(result.newTokenValue()).build();
    }

    @Transactional
    public void completeProfile(Long userId, ProfileRequestDto request) {
        // 1. 유저 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("유저를 찾을 수 없습니다."));

        // 2. 이미 프로필이 완성된 유저인지 검증 (이메일, 닉네임 존재 여부)
        if (user.getEmail() != null || user.getNickname() != null) {
            throw new BusinessException("이미 프로필이 완성된 유저입니다.");
        }

        // 3. 입력된 정보에 대한 중복 검사
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException("이미 사용중인 이메일입니다.");
        }
        if (userRepository.existsByNickname(request.nickname())) {
            throw new BusinessException("이미 사용중인 닉네임입니다.");
        }

        // 4. 유저 정보 업데이트
        user.updateProfile(
                request.email(),
                request.nickname(),
                request.gender(),
                request.birthYear()
        );
    }

    // 단일 기기 로그아웃
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.deleteByToken(refreshToken);
    }

    @Transactional
    public TokenResponseDto exchangeOAuthToken(String tempToken) {
        Long userId = oAuthTempTokenService.consume(tempToken);
        String accessToken = jwtTokenProvider.createAccessToken(userId);
        String refreshToken = refreshTokenService.issue(userId);
        return TokenResponseDto.builder().accessToken(accessToken).refreshToken(refreshToken).build();
    }
}
