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
import com.crates.crates.repository.OnboardingContentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentService {

    /**
     * 제목 검색 최소 길이. pg_trgm의 트라이그램이 3글자 단위라, 이보다 짧으면
     * gin_trgm_ops 인덱스를 쓸 수 없고 전체 스캔이 된다. 임의로 정한 값이 아니다.
     */
    private static final int MIN_SEARCH_KEYWORD_LENGTH = 3;

    private final ContentRepository contentRepository;
    private final BoardRepository boardRepository;
    private final BoardItemRepository boardItemRepository;
    private final ImageService imageService;
    private final MovieService movieService;
    private final BookService bookService;
    private final MusicService musicService;
    private final OnboardingContentRepository onboardingContentRepository;

    public ContentResponseDto getContentSummary(Long contentId)
    {
        ContentQueryDto dto = contentRepository.findContentSummaryById(contentId)
                .orElseThrow(() -> new BusinessException("콘텐츠를 찾을 수 없습니다. contentId: " + contentId));

        String imageUrl = imageService.getImageUrl(dto.s3ObjectKey(), dto.imageExtension());

        return ContentResponseDto.of(dto, imageUrl);
    }

    /**
     * 콘텐츠 요약을 <b>전달받은 id 순서 그대로</b> 돌려준다.
     *
     * <p>호출하는 쪽이 넘기는 순서에는 의미가 있다. 보드 조회는 사용자가 배치한 슬롯 순서이고,
     * 추천/검색은 Qdrant가 매긴 유사도 순위다. 그런데 findContentsByIds는 IN 절 결과를
     * DB가 주는 순서로 돌려주므로, 여기서 복원하지 않으면 그 의미가 조용히 사라진다.</p>
     *
     * <p>id에 해당하는 콘텐츠가 없으면 그 자리는 건너뛴다. Qdrant에는 남아 있지만 RDB에서
     * 사라진 콘텐츠가 섞여 들어올 수 있어서, 결과가 입력보다 짧아질 수 있다.</p>
     */
    public List<ContentResponseDto> getContentSummaries(List<Long> contentIds)
    {
        if (contentIds.isEmpty())
        {
            return Collections.emptyList();
        }

        Map<Long, ContentResponseDto> summaryById = contentRepository.findContentsByIds(contentIds).stream()
                .collect(Collectors.toMap(
                        ContentQueryDto::id,
                        dto -> ContentResponseDto.of(
                                dto,
                                imageService.getImageUrl(dto.s3ObjectKey(), dto.imageExtension()))));

        return contentIds.stream()
                .map(summaryById::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 제목으로 콘텐츠를 검색한다. 보드 수정 화면에서 교체할 콘텐츠를 고를 때 쓴다.
     *
     * <p>검색어는 최소 {@value #MIN_SEARCH_KEYWORD_LENGTH}글자여야 한다. 트라이그램 인덱스가
     * 3글자 단위라 그보다 짧으면 인덱스를 못 쓰고 191,239행을 전부 훑는다(실측 2글자 155ms).
     * 화면에서 한 글자씩 칠 때마다 호출되는 API라 이 차이가 그대로 체감된다.</p>
     */
    public List<ContentResponseDto> searchByTitle(String keyword, int page, int size)
    {
        String normalized = keyword.strip();
        if (normalized.length() < MIN_SEARCH_KEYWORD_LENGTH)
        {
            throw new BusinessException(
                    "검색어는 " + MIN_SEARCH_KEYWORD_LENGTH + "글자 이상 입력해주세요.");
        }

        List<Long> contentIds = contentRepository.searchContentIdsByTitle(normalized, size, page * size);

        // 검색 순위(유사도 순)를 그대로 유지한다.
        return getContentSummaries(contentIds);
    }

    /**
     * 가입 직후 고를 수 있는 한 종류의 후보 콘텐츠를 인기순으로 돌려준다(종류마다 최대 200개).
     * 화면이 종류별 탭으로 나뉘어 있어 한 번에 한 종류만 받는다.
     */
    public List<ContentResponseDto> getOnboardingContents(String type)
    {
        String dtype = type.toUpperCase();
        if (!List.of(Book.DTYPE, Movie.DTYPE, Music.DTYPE).contains(dtype))
        {
            throw new BusinessException("지원하지 않는 콘텐츠 타입입니다: " + type);
        }
        return getContentSummaries(onboardingContentRepository.findContentIdsByDtype(dtype));
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

    // 보드의 컨텐츠 목록 조회. 사용자가 배치한 슬롯 순서(slot_no)를 그대로 유지한다.
    public List<ContentResponseDto> getContents(Long boardId)
    {
        if (!boardRepository.existsById(boardId)) {
            throw new BusinessException("존재하지 않는 보드입니다. boardId: " + boardId);
        }

        // 슬롯 순으로 정렬된 id를 넘기면 getContentSummaries가 그 순서를 유지해준다.
        return getContentSummaries(boardItemRepository.findContentIdsByBoardId(boardId));
    }
}
