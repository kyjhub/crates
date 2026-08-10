package com.crates.crates.controller;

import com.crates.crates.DTO.ApiResponse;
import com.crates.crates.service.SearchService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Validated
public class SearchController {

    private final SearchService searchService;

    // 검색어를 AI 서버로 전달해 벡터를 계산한다. 벡터 자체는 프론트로 내려주지 않고,
    // 추후 유사 콘텐츠 조회(ContentVectorService 연동)에 내부적으로 사용할 예정이다.
    @GetMapping("/vector")
    public ResponseEntity<ApiResponse<Void>> requestQueryVector(
            @RequestParam @NotBlank(message = "검색어를 입력해주세요.") @Size(max = 100, message = "검색어는 100자를 초과할 수 없습니다.") String keyword)
    {
        searchService.getQueryVector(keyword);
        return ResponseEntity.ok(new ApiResponse<>(true, null, "검색어 벡터 계산 완료"));
    }
}
