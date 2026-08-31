package com.crates.crates.service;

import com.crates.crates.DTO.BoardContentIdDto;
import com.crates.crates.DTO.BoardLikeRequest;
import com.crates.crates.DTO.BoardLikeResponse;
import com.crates.crates.DTO.BoardWithContentsDto;
import com.crates.crates.DTO.ContentResponseDto;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.board.Board;
import com.crates.crates.entity.board.BoardFeedback;
import com.crates.crates.entity.contents.Content;
import com.crates.crates.entity.user.User;
import com.crates.crates.enumData.BoardType;
import com.crates.crates.enumData.Rating;
import com.crates.crates.repository.BoardFeedbackRepository;
import com.crates.crates.repository.BoardItemRepository;
import com.crates.crates.repository.BoardRepository;
import com.crates.crates.repository.ContentRepository;
import com.crates.crates.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardService {

    /** 보드 하나에 담기는 콘텐츠 수. 서비스 규칙상 고정값이라 저장 시점에 검증한다. */
    private static final int ITEMS_PER_BOARD = 8;

    private final BoardRepository boardRepository;
    private final BoardItemRepository boardItemRepository;
    private final BoardFeedbackRepository boardFeedbackRepository;
    private final ContentRepository contentRepository;
    private final UserRepository userRepository;
    private final ContentService contentService;

    public BoardWithContentsDto getBoardWithContents(Long boardId, Long userId)
    {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new BusinessException("존재하지 않는 보드입니다. boardId: " + boardId));
        List<ContentResponseDto> contents = contentService.getContents(boardId);
        boolean liked = boardFeedbackRepository
                .existsByBoardIdAndUserIdAndRating(boardId, userId, Rating.LIKE);

        return new BoardWithContentsDto(board.getId(), board.getTitle(), board.getLikeCount(), liked, contents);
    }

    public List<BoardWithContentsDto> getLikedBoards(int n, Long userId)
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
                .collect(Collectors.toMap(ContentResponseDto::id, Function.identity()));

        // 보드마다 조회하면 N번 왕복하므로 내가 좋아요한 보드 id를 한 번에 가져와 대조한다.
        Set<Long> likedBoardIds = Set.copyOf(findLikedBoardIds(userId, boardIds));

        return topBoards.stream()
                .map(board -> new BoardWithContentsDto(
                        board.getId(),
                        board.getTitle(),
                        board.getLikeCount(),
                        likedBoardIds.contains(board.getId()),
                        contentIdsByBoardId.getOrDefault(board.getId(), List.of()).stream()
                                .map(summaryById::get)
                                .filter(Objects::nonNull)
                                .toList()
                ))
                .toList();
    }

    /**
     * 이미 저장된 보드에 좋아요를 누른다.
     *
     * <p>연타나 재요청으로 같은 요청이 두 번 와도 카운트가 두 번 오르지 않도록 멱등하게 동작한다.
     * 예외를 던지지 않는 이유는, 이미 누른 상태라는 것이 사용자 입장에서 실패가 아니기 때문이다.
     */
    @Transactional
    public BoardLikeResponse like(Long boardId, Long userId)
    {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new BusinessException("존재하지 않는 보드입니다. boardId: " + boardId));

        if (boardFeedbackRepository.existsByBoardIdAndUserIdAndRating(boardId, userId, Rating.LIKE))
        {
            return new BoardLikeResponse(boardId, currentLikeCount(boardId), true);
        }

        saveFeedback(board, userId);
        boardRepository.incrementLikeCount(boardId);

        return new BoardLikeResponse(boardId, currentLikeCount(boardId), true);
    }

    /** 좋아요 취소. 누른 적이 없으면 카운트를 건드리지 않는다. */
    @Transactional
    public BoardLikeResponse unlike(Long boardId, Long userId)
    {
        if (!boardRepository.existsById(boardId))
        {
            throw new BusinessException("존재하지 않는 보드입니다. boardId: " + boardId);
        }

        int removed = boardFeedbackRepository.deleteFeedback(boardId, userId, Rating.LIKE);
        if (removed > 0)
        {
            boardRepository.decrementLikeCount(boardId);
        }

        return new BoardLikeResponse(boardId, currentLikeCount(boardId), false);
    }

    /**
     * 아직 DB에 없는 보드("오늘의 추천 보드", 검색어 유사 보드)에 좋아요를 누른 경우.
     * 보드를 먼저 저장한 뒤 좋아요를 남긴다. 이 경로로 저장되는 보드는 전부 AI_RECOMMEND다.
     *
     * <p>응답의 boardId를 프론트가 반드시 반영해야 같은 보드가 두 번 저장되지 않는다.
     */
    @Transactional
    public BoardLikeResponse likeNewBoard(BoardLikeRequest request, Long userId)
    {
        if (request.contentIds().size() != ITEMS_PER_BOARD)
        {
            throw new BusinessException(
                    "보드에는 콘텐츠 " + ITEMS_PER_BOARD + "건이 필요합니다. 요청: " + request.contentIds().size());
        }

        List<Content> contents = contentRepository.findAllById(request.contentIds());
        if (contents.size() != request.contentIds().size())
        {
            throw new BusinessException("보드에 담을 수 없는 콘텐츠가 포함되어 있습니다.");
        }

        Board board = Board.builder()
                .boardType(BoardType.AI_RECOMMEND)
                .title(request.title())
                .likeCount(1L)
                .createdAt(LocalDateTime.now())
                .build();
        // BoardItem은 cascade = ALL이라 보드를 저장할 때 함께 저장된다.
        contents.forEach(board::addContent);
        boardRepository.save(board);

        saveFeedback(board, userId);

        return new BoardLikeResponse(board.getId(), board.getLikeCount(), true);
    }

    /** 화면에 보이는 보드들 중 이 사용자가 좋아요한 것들의 id. 보드마다 조회하지 않도록 한 번에 가져온다. */
    public List<Long> findLikedBoardIds(Long userId, List<Long> boardIds)
    {
        if (boardIds.isEmpty())
        {
            return Collections.emptyList();
        }
        return boardFeedbackRepository.findLikedBoardIds(userId, Rating.LIKE, boardIds);
    }

    private void saveFeedback(Board board, Long userId)
    {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("존재하지 않는 사용자입니다. userId: " + userId));

        boardFeedbackRepository.save(BoardFeedback.builder()
                .board(board)
                .user(user)
                .rating(Rating.LIKE)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private Long currentLikeCount(Long boardId)
    {
        return boardRepository.findLikeCountById(boardId).orElse(0L);
    }
}
