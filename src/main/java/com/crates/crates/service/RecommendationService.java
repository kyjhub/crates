package com.crates.crates.service;

import com.crates.crates.DTO.BoardWithContentsDto;
import com.crates.crates.DTO.ContentResponseDto;
import com.crates.crates.entity.board.Board;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    /** 홈 화면에 한 줄로 보여줄 추천 보드 수. 스크롤 없이 4개만 노출한다. */
    private static final int RECOMMENDED_BOARD_COUNT = 4;

    private final UserVectorService userVectorService;
    private final ContentVectorService contentVectorService;
    private final ContentService contentService;
    private final BoardService boardService;
    private final BoardTitleService boardTitleService;

    /** 유사도 순으로 고른 콘텐츠와 그 벡터. 벡터는 보드 제목을 정할 때 쓴다. */
    public record SimilarContents(List<ContentResponseDto> contents, Map<Long, float[]> vectorById) {

        /** slice에 담긴 콘텐츠의 벡터. Qdrant 응답에 없던 id는 빠진다. */
        public List<float[]> vectorsOf(List<ContentResponseDto> slice)
        {
            return slice.stream()
                    .map(content -> vectorById.get(content.id()))
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

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
        SimilarContents similar = findSimilar(
                userVectorService.getVector(userId), Board.ITEMS_PER_BOARD * RECOMMENDED_BOARD_COUNT);
        List<ContentResponseDto> contents = similar.contents();

        List<BoardWithContentsDto> boards = new ArrayList<>(RECOMMENDED_BOARD_COUNT);
        for (int from = 0; from + Board.ITEMS_PER_BOARD <= contents.size(); from += Board.ITEMS_PER_BOARD)
        {
            // 8건을 못 채우는 나머지는 버린다. 모자란 보드는 좋아요를 눌러도 저장이 거절된다.
            // subList는 원본을 들여다보는 뷰라, 응답에 그대로 담지 않고 복사한다.
            List<ContentResponseDto> slice =
                    List.copyOf(contents.subList(from, from + Board.ITEMS_PER_BOARD));

            // 같은 구성의 보드가 이미 저장돼 있으면 저장된 제목을 쓰므로, query 매칭은 미저장일 때만 한다.
            // 매칭(Qdrant 검색)은 트랜잭션 밖인 여기서 한다. BoardService 안에서 하면 커넥션을 쥐고 기다린다.
            // 제목은 보드 콘텐츠의 평균 벡터와 가장 가까운 query다(BoardTitleService).
            boards.add(boardService.findSavedGeneratedBoard(slice, userId)
                    .orElseGet(() -> BoardWithContentsDto.unsaved(
                            boardTitleService.titleFor(similar.vectorsOf(slice)), slice)));
        }

        return boards;
    }

    /**
     * 기준 벡터와 가까운 콘텐츠 요약과 벡터를 유사도 높은 순으로 돌려준다.
     *
     * <p>사용자 취향 벡터와 검색어 벡터가 같은 임베딩 공간에 있으므로 조회 경로를 공유한다.
     * 호출하는 쪽은 벡터의 출처를 신경 쓰지 않는다.</p>
     *
     * <p>Qdrant가 매긴 순위는 getContentSummaries가 입력 순서를 유지해주므로 그대로 보존된다.
     * Qdrant에는 남아 있지만 RDB에서 사라진 콘텐츠도 거기서 함께 걸러진다.</p>
     */
    public SimilarContents findSimilar(float[] targetVector, int topN)
    {
        Map<Long, float[]> vectorById = contentVectorService.findSimilar(targetVector, topN);

        return new SimilarContents(contentService.getContentSummaries(List.copyOf(vectorById.keySet())), vectorById);
    }
}
