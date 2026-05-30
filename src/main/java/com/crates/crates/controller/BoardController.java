package com.crates.crates.controller;


import com.crates.crates.DTO.ApiResponse;
import com.crates.crates.DTO.ContentResponseDto;
import com.crates.crates.service.ContentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/board")
@RequiredArgsConstructor
public class BoardController {

    private final ContentService contentService;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<ContentResponseDto>>> getBoard(
            @RequestParam Long boardId)
    {
        List<ContentResponseDto> contentResponseDtoList = contentService.getContents(boardId);

        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, contentResponseDtoList, "보드 조회 성공"));
    }
}
