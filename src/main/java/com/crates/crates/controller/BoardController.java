package com.crates.crates.controller;


import com.crates.crates.DTO.ApiResponse;
import com.crates.crates.DTO.BoardWithContentsDto;
import com.crates.crates.DTO.ContentResponseDto;
import com.crates.crates.service.BoardService;
import com.crates.crates.service.RecommendationService;
import com.crates.crates.user.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/boards")
@RequiredArgsConstructor
public class BoardController {

    private final RecommendationService recommendationService;
    private final BoardService boardService;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<BoardWithContentsDto>> getBoard(@RequestParam Long boardId)
    {
        BoardWithContentsDto board = boardService.getBoardWithContents(boardId);

        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, board, "보드 조회 성공"));
    }

    //- 사용자 벡터값 기반 추천 보드 (컨텐츠 요약 정보)
    @GetMapping("/recommendation/user")
    public ResponseEntity<ApiResponse<List<ContentResponseDto>>> getRecommendation(@AuthenticationPrincipal CustomUserDetails userDetails, @RequestParam int n)
    {
        Long userId = userDetails.getUserId();
        List<ContentResponseDto> result = recommendationService.recommend(userId, n);

        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, result, "추천 보드 조회 성공"));
    }

    //- 다른 사용자들에게 좋아요를 많이 받은 보드 (컨텐츠 요약 정보)
    @GetMapping("/liked")
    public ResponseEntity<ApiResponse<List<BoardWithContentsDto>>> getLiked(@RequestParam int n)
    {
        List<BoardWithContentsDto> result = boardService.getLikedBoards(n);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>(true, result, "인기 보드 조회 성공"));
    }

    //- 사용자가 직접 제작한 보드 [사용자가 제목도 직접 작성]
    //- 검색어와 벡터값이 유사한 컨텐츠를 모아놓은 보드 [사용자의 검색과 동시에 보여줘야함] [ai서버에서 검색어 벡터값 받아야함]
}
