package com.crates.crates.controller;

import com.crates.crates.DTO.ApiResponse;
import com.crates.crates.DTO.InitialContentsRequest;
import com.crates.crates.DTO.OnboardingStatusResponse;
import com.crates.crates.service.UserVectorService;
import com.crates.crates.user.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserController {

    private final UserVectorService userVectorService;

    // 가입 후 단계가 끝났는지. OAuth 콜백 직후와 화면 진입 때 프론트가 확인한다.
    @GetMapping("/onboarding")
    public ResponseEntity<ApiResponse<OnboardingStatusResponse>> getOnboardingStatus(
            @AuthenticationPrincipal CustomUserDetails userDetails)
    {
        boolean selected = userVectorService.hasVector(userDetails.getUserId());
        return ResponseEntity.ok(new ApiResponse<>(true, new OnboardingStatusResponse(selected), "온보딩 상태 조회 성공"));
    }

    // 가입 직후 고른 취향 콘텐츠(1~10개)로 취향 벡터를 만든다. 사용자마다 한 번만 받는다.
    @PostMapping("/initial-contents")
    public ResponseEntity<ApiResponse<Void>> selectInitialContents(
            @Valid @RequestBody InitialContentsRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails)
    {
        userVectorService.initialize(userDetails.getUserId(), request.contentIds());
        return ResponseEntity.ok(new ApiResponse<>(true, null, "취향 콘텐츠 선택 완료"));
    }
}
