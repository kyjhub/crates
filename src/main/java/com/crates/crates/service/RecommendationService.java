package com.crates.crates.service;

import com.crates.crates.DTO.BoardWithContentsDto;
import com.crates.crates.DTO.ContentResponseDto;
import com.crates.crates.entity.board.Board;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    /** 홈 화면에 한 줄로 보여줄 추천 보드 수. 스크롤 없이 4개만 노출한다. */
    private static final int RECOMMENDED_BOARD_COUNT = 4;

    /**
     * "오늘의 추천 보드"의 임시 제목.
     *
     * <p>최종적으로는 보드 콘텐츠의 평균 벡터와 가장 가까운 query가 제목이 된다.
     * Qdrant의 query_vector 컬렉션이 아직 비어 있어 그 전까지 쓰는 값이다.
     * (docs/board-schema.md 4장)</p>
     */
    private static final String RECOMMENDATION_TITLE = "오늘의 추천 보드";

    private final UserVectorService userVectorService;
    private final ContentVectorService contentVectorService;
    private final ContentService contentService;
    private final BoardService boardService;

    /**
     * 사용자 취향 벡터와 가까운 콘텐츠로 보드 한 장을 만든다.
     *
     * <p>검색 보드와 마찬가지로 저장하지 않는다. 다만 같은 구성의 보드가 이미 저장돼 있으면
     * 그 boardId와 좋아요 상태를 함께 내려준다. 그래야 좋아요를 누른 뒤 새로고침해도
     * 하트가 채워진 상태로 남는다.</p>
     */
    public List<BoardWithContentsDto> recommendBoards(Long userId)
    {
        // 유사도 상위 32건을 받아 8건씩 끊어 4개 보드로 만든다.
        // 앞쪽 보드일수록 취향에 가깝다.
        List<ContentResponseDto> contents =
                recommend(userId, Board.ITEMS_PER_BOARD * RECOMMENDED_BOARD_COUNT);

        List<BoardWithContentsDto> boards = new ArrayList<>(RECOMMENDED_BOARD_COUNT);
        for (int from = 0; from + Board.ITEMS_PER_BOARD <= contents.size(); from += Board.ITEMS_PER_BOARD)
        {
            // 8건을 못 채우는 나머지는 버린다. 모자란 보드는 좋아요를 눌러도 저장이 거절된다.
            // subList는 원본을 들여다보는 뷰라, 응답에 그대로 담지 않고 복사한다.
            List<ContentResponseDto> slice =
                    List.copyOf(contents.subList(from, from + Board.ITEMS_PER_BOARD));

            boards.add(boardService.resolveGeneratedBoard(titleFor(boards.size()), slice, userId));
        }

        return boards;
    }

    /**
     * 보드가 여러 개라 제목이 겹치지 않게 번호를 붙인다.
     *
     * <p>7단계에서 콘텐츠 평균 벡터와 가장 가까운 query로 대체될 임시 규칙이다.
     * 좋아요를 눌러 저장된 보드의 제목은 그 시점 값으로 고정되므로, 이미 저장된 보드는
     * 나중에도 이 번호 제목을 유지한다.</p>
     */
    private String titleFor(int index)
    {
        return RECOMMENDATION_TITLE + " " + (index + 1);
    }

    /**
     * 사용자 취향 벡터 기반 추천.
     *
     * <p>벡터가 비어 있는 경우를 따로 다루지 않는다. 가입 시점에 L2 정규화된 랜덤 벡터가 들어가고
     * 좋아요가 생기면 그 집합에서 다시 계산되므로, 언제 조회하든 Cosine 검색에 쓸 수 있는 값이 있다.</p>
     */
    public List<ContentResponseDto> recommend(Long userId, int topN)
    {
        return recommendByVector(userVectorService.resolveVector(userId), topN);
    }

    /**
     * 기준 벡터와 가까운 콘텐츠 요약을 유사도 높은 순으로 돌려준다.
     *
     * <p>사용자 취향 벡터와 검색어 벡터가 같은 임베딩 공간에 있으므로 조회 경로를 공유한다.
     * 호출하는 쪽은 벡터의 출처를 신경 쓰지 않는다.</p>
     *
     * <p>Qdrant가 매긴 순위는 getContentSummaries가 입력 순서를 유지해주므로 그대로 보존된다.
     * Qdrant에는 남아 있지만 RDB에서 사라진 콘텐츠도 거기서 함께 걸러진다.</p>
     */
    public List<ContentResponseDto> recommendByVector(float[] targetVector, int topN)
    {
        List<Long> similarContentIds = contentVectorService.findSimilarContentIds(targetVector, topN);

        return contentService.getContentSummaries(similarContentIds);
    }
}
