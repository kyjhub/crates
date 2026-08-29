package com.crates.crates.service;

import com.crates.crates.DTO.ContentDetailResponse;
import com.crates.crates.DTO.ContentQueryDto;
import com.crates.crates.DTO.ContentResponseDto;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.repository.BoardItemRepository;
import com.crates.crates.repository.BoardRepository;
import com.crates.crates.entity.contents.Book;
import com.crates.crates.entity.contents.Movie;
import com.crates.crates.entity.contents.Music;
import com.crates.crates.repository.ContentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentService {

    private final ContentRepository contentRepository;
    private final BoardRepository boardRepository;
    private final BoardItemRepository boardItemRepository;
    private final ImageService imageService;
    private final MovieService movieService;
    private final BookService bookService;
    private final MusicService musicService;

    public ContentResponseDto getContentSummary(Long contentId)
    {
        ContentQueryDto dto = contentRepository.findContentSummaryById(contentId)
                .orElseThrow(() -> new BusinessException("콘텐츠를 찾을 수 없습니다. contentId: " + contentId));

        String imageUrl = imageService.getImageUrl(dto.s3ObjectKey(), dto.imageExtension());

        return ContentResponseDto.of(dto, imageUrl);
    }

    public List<ContentResponseDto> getContentSummaries(List<Long> contentIds)
    {
        if (contentIds.isEmpty())
        {
            return Collections.emptyList();
        }

        List<ContentQueryDto> contentQueryDtoList = contentRepository.findContentsByIds(contentIds);

        return contentQueryDtoList.stream()
                .map(dto ->
                {
                    String imageUrl = imageService.getImageUrl(dto.s3ObjectKey(), dto.imageExtension());

                    return ContentResponseDto.of(dto, imageUrl);
                })
                .toList();
    }

    public ContentDetailResponse getContentDetail(String dtype, Long contentId)
    {
        return switch (dtype.toUpperCase()) {
            case Movie.DTYPE -> movieService.getDetail(contentId);
            case Book.DTYPE -> bookService.getDetail(contentId);
            case Music.DTYPE -> musicService.getDetail(contentId);
            default -> throw new BusinessException("지원하지 않는 콘텐츠 타입입니다: " + dtype);
        };
    }

    // 보드의 컨텐츠 목록 조회
    public List<ContentResponseDto> getContents(Long boardId)
    {
        if (!boardRepository.existsById(boardId)) {
            throw new BusinessException("존재하지 않는 보드입니다. boardId: " + boardId);
        }

        List<Long> contentsId = boardItemRepository.findContentIdsByBoardId(boardId);

        if (contentsId.isEmpty()) {
            return Collections.emptyList();
        }

        List<ContentQueryDto> contentQueryDtoList = contentRepository.findContentsByIds(contentsId);

        return contentQueryDtoList.stream().map(dto ->
                {
                    String imageUrl = imageService.getImageUrl(dto.s3ObjectKey(), dto.imageExtension());

                    return ContentResponseDto.of(dto, imageUrl);
                }
        ).toList();
    }
}
