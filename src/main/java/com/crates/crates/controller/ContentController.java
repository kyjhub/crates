package com.crates.crates.controller;

import com.crates.crates.DTO.ApiResponse;
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
    public ResponseEntity<ApiResponse<ContentResponseDto>> getContent(@PathVariable Long contentId)
    {
        ContentResponseDto contentResponseDto = contentService.getContent(contentId);
        return ResponseEntity.ok(new ApiResponse<>(true, contentResponseDto, "컨텐츠 조회 성공"));
    }
}
