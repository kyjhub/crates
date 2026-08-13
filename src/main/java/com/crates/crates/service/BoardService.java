package com.crates.crates.service;

import com.crates.crates.DTO.BoardContentIdDto;
import com.crates.crates.DTO.BoardWithContentsDto;
import com.crates.crates.DTO.ContentResponseDto;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.board.Board;
import com.crates.crates.repository.BoardItemRepository;
import com.crates.crates.repository.BoardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardService {

    private final BoardRepository boardRepository;
    private final BoardItemRepository boardItemRepository;
    private final ContentService contentService;

    public BoardWithContentsDto getBoardWithContents(Long boardId)
    {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new BusinessException("존재하지 않는 보드입니다. boardId: " + boardId));
        List<ContentResponseDto> contents = contentService.getContents(boardId);

        return new BoardWithContentsDto(board.getId(), board.getTitle(), board.getLikeCount(), contents);
    }

    public List<BoardWithContentsDto> getLikedBoards(int n)
    {
        List<Board> topBoards = boardRepository.findByDeletedAtIsNullOrderByLikeCountDesc(PageRequest.of(0, n));
        if (topBoards.isEmpty())
        {
            return Collections.emptyList();
        }

        List<Long> boardIds = topBoards.stream()
                .map(Board::getId)
                .toList();
        List<BoardContentIdDto> pairs = boardItemRepository.findContentIdsByBoardIds(boardIds);

        Map<Long, List<Long>> contentIdsByBoardId = pairs.stream()
                .collect(Collectors.groupingBy(
                        BoardContentIdDto::boardId,
                        Collectors.mapping(BoardContentIdDto::contentId, Collectors.toList())
                ));

        List<Long> allContentIds = pairs.stream()
                .map(BoardContentIdDto::contentId)
                .distinct()
                .toList();
        Map<Long, ContentResponseDto> summaryById = contentService.getContentSummaries(allContentIds).stream()
                .collect(Collectors.toMap(ContentResponseDto::getId, Function.identity()));

        return topBoards.stream()
                .map(board -> new BoardWithContentsDto(
                        board.getId(),
                        board.getTitle(),
                        board.getLikeCount(),
                        contentIdsByBoardId.getOrDefault(board.getId(), List.of()).stream()
                                .map(summaryById::get)
                                .filter(Objects::nonNull)
                                .toList()
                ))
                .toList();
    }
}
