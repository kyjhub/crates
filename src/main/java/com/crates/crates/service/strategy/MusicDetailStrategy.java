package com.crates.crates.service.strategy;

import com.crates.crates.DTO.ContentDetailResponse;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.contents.Music;
import com.crates.crates.repository.MusicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MusicDetailStrategy implements ContentDetailStrategy {

    private final MusicRepository musicRepository;

    @Override
    public String getSupportedType() {
        return Music.DTYPE;
    }

    @Override
    public ContentDetailResponse getDetail(Long contentId) {
        return musicRepository.findMusicDetailById(contentId)
                .orElseThrow(() -> new BusinessException("음악을 찾을 수 없습니다. id: " + contentId));
    }
}
