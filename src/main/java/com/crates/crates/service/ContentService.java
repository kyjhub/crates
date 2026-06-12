package com.crates.crates.service;

import com.crates.crates.DTO.ContentQueryDto;
import com.crates.crates.DTO.ContentResponseDto;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.contents.Content;
import com.crates.crates.repository.BoardItemRepository;
import com.crates.crates.repository.BoardRepository;
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

    // 컨텐츠 단건 조회
    public ContentResponseDto getContent(Long contentId) {
        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new BusinessException("콘텐츠를 찾을 수 없습니다. contentId: " + contentId));

        // 이미 프론트에서 board를 받으면서 이미지를 갖고있을텐데 내가 또 넘겨줄 이유가 있을까? <- 컨텐츠 검색에서 필요할듯
        String imageUrl = imageService.getImageUrl(content.getS3ObjectKey(), content.getImageExtension());

        // 자식클래스의 정보까지 담아서 반환해야됨-> DTO새로 만들어서 ㄱㄱ
//        return ContentResponseDto.builder()
//                .id(content.getId())
//                .title(content.getTitle())
//                .imageUrl(imageUrl)
//                .contentType(content.getDtype())
//                .releaseDate(content.getReleaseDate())
//                .build();
        return null;
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
                            .build();
                }
        ).toList();
    }
}
