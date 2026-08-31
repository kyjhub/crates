package com.crates.crates.service;

import com.crates.crates.DTO.ContentResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final UserVectorService userVectorService;
    private final ContentVectorService contentVectorService;
    private final ContentService contentService;

    // user_vector 기반 추천 -> 제목도 같이 내보내도록 코드 수정 필요
    public List<ContentResponseDto> recommend(Long userId, int topN)
    {
        float[] userVector = userVectorService.getVector(userId);
        List<Long> similarContentIds = contentVectorService.findSimilarContentIds(userVector, topN);
        return contentService.getContentSummaries(similarContentIds);
    }

    // 검색어 vector 기반 추천
}
