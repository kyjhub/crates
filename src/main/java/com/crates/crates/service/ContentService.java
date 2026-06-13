package com.crates.crates.service;

import com.crates.crates.DTO.ContentDetailResponse;
import com.crates.crates.DTO.ContentQueryDto;
import com.crates.crates.DTO.ContentResponseDto;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.repository.BoardItemRepository;
import com.crates.crates.repository.BoardRepository;
import com.crates.crates.repository.ContentRepository;
import com.crates.crates.service.strategy.ContentDetailStrategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ContentService {

    private final ContentRepository contentRepository;
    private final BoardRepository boardRepository;
    private final BoardItemRepository boardItemRepository;
    private final ImageService imageService;
    private final Map<String, ContentDetailStrategy> strategyMap;

    public ContentService(ContentRepository contentRepository,
                          BoardRepository boardRepository,
                          BoardItemRepository boardItemRepository,
                          ImageService imageService,
                          List<ContentDetailStrategy> strategies)
    {
        this.contentRepository = contentRepository;
        this.boardRepository = boardRepository;
        this.boardItemRepository = boardItemRepository;
        this.imageService = imageService;
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(ContentDetailStrategy::getSupportedType, Function.identity()));
    }

    public ContentResponseDto getContentSummary(Long contentId)
    {
        ContentQueryDto dto = contentRepository.findContentSummaryById(contentId)
                .orElseThrow(() -> new BusinessException("콘텐츠를 찾을 수 없습니다. contentId: " + contentId));

        String imageUrl = imageService.getImageUrl(dto.getS3ObjectKey(), dto.getImageExtension());

        return ContentResponseDto.builder()
                .id(dto.getId())
                .title(dto.getTitle())
                .imageUrl(imageUrl)
                .contentType(dto.getContentType())
                .releaseDate(dto.getReleaseDate())
                .genre(dto.getGenre())
                .build();
    }

    public ContentDetailResponse getContentDetail(String dtype, Long contentId)
    {
        return Optional.ofNullable(strategyMap.get(dtype.toUpperCase()))
                .orElseThrow(() -> new BusinessException("지원하지 않는 콘텐츠입니다: " + dtype))
                .getDetail(contentId);
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
                    String imageUrl = imageService.getImageUrl(dto.getS3ObjectKey(), dto.getImageExtension());

                    return ContentResponseDto.builder()
                            .id(dto.getId())
                            .title(dto.getTitle())
                            .imageUrl(imageUrl)
                            .contentType(dto.getContentType())
                            .releaseDate(dto.getReleaseDate())
                            .genre(dto.getGenre())
                            .build();
                }
        ).toList();
    }
}
