package com.crates.crates.controller;

import com.crates.crates.DTO.ApiResponse;
import com.crates.crates.DTO.ContentDetailResponse;
import com.crates.crates.DTO.ContentResponseDto;
import com.crates.crates.service.ContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contents")
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;

    @GetMapping("/{contentId}")
    public ResponseEntity<ApiResponse<ContentResponseDto>> getContentSummary(@PathVariable Long contentId)
    {
        ContentResponseDto contentResponseDto = contentService.getContentSummary(contentId);
        return ResponseEntity.ok(new ApiResponse<>(true, contentResponseDto, "컨텐츠 요약 조회 성공"));
    }

    @GetMapping("/{dtype}/{contentId}")
    public ResponseEntity<ApiResponse<ContentDetailResponse>> getContentDetail(
            @PathVariable String dtype,
            @PathVariable Long contentId)
    {
        ContentDetailResponse contentDetailResponse = contentService.getContentDetail(dtype, contentId);
        return ResponseEntity.ok(new ApiResponse<>(true, contentDetailResponse, "컨텐츠 상세 조회 성공"));
    }


}
