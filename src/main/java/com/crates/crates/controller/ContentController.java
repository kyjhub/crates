package com.crates.crates.controller;

import com.crates.crates.DTO.ApiResponse;
import com.crates.crates.DTO.ContentDetailResponse;
import com.crates.crates.DTO.ContentResponseDto;
import com.crates.crates.service.ContentService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/contents")
@RequiredArgsConstructor
@Validated
public class ContentController {

    private final ContentService contentService;

    // 제목 검색. 보드 수정 화면에서 교체할 콘텐츠를 고를 때 호출한다.
    // 세로 스크롤로 더 보기 위해 page/size로 나눠 받는다.
    // 경로가 "/{contentId}"와 겹치지 않도록 단건 조회보다 먼저 선언한다.
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<ContentResponseDto>>> searchContents(
            @RequestParam @NotBlank(message = "검색어를 입력해주세요.")
            @Size(max = 100, message = "검색어는 100자를 초과할 수 없습니다.") String keyword,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size는 1 이상이어야 합니다.")
            @Max(value = 50, message = "size는 50을 초과할 수 없습니다.") int size)
    {
        List<ContentResponseDto> result = contentService.searchByTitle(keyword, page, size);
        return ResponseEntity.ok(new ApiResponse<>(true, result, "콘텐츠 검색 성공"));
    }

    // 가입 직후 취향 콘텐츠 후보. 종류(BOOK, MOVIE, MUSIC)별 인기순 목록이며, 화면의 탭 하나가 한 번 호출한다.
    @GetMapping("/onboarding")
    public ResponseEntity<ApiResponse<List<ContentResponseDto>>> getOnboardingContents(
            @RequestParam @NotBlank(message = "콘텐츠 타입을 입력해주세요.") String type)
    {
        List<ContentResponseDto> result = contentService.getOnboardingContents(type);
        return ResponseEntity.ok(new ApiResponse<>(true, result, "가입 취향 콘텐츠 후보 조회 성공"));
    }

    // 단건 요약 조회
    @GetMapping("/{contentId}")
    public ResponseEntity<ApiResponse<ContentResponseDto>> getContentSummary(@PathVariable Long contentId)
    {
        ContentResponseDto contentResponseDto = contentService.getContentSummary(contentId);
        return ResponseEntity.ok(new ApiResponse<>(true, contentResponseDto, "컨텐츠 요약 조회 성공"));
    }

    // 단건 상세 조회
    @GetMapping("/{dtype}/{contentId}")
    public ResponseEntity<ApiResponse<ContentDetailResponse>> getContentDetail(
            @PathVariable String dtype,
            @PathVariable Long contentId)
    {
        ContentDetailResponse contentDetailResponse = contentService.getContentDetail(dtype, contentId);
        return ResponseEntity.ok(new ApiResponse<>(true, contentDetailResponse, "컨텐츠 상세 조회 성공"));
    }

    // 배치 상세 조회
}
