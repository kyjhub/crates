package com.crates.crates.service;

import com.crates.crates.DTO.MusicResponseDTO;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.repository.MusicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MusicService {

    private final MusicRepository musicRepository;

    public MusicResponseDTO getDetail(Long contentId) {
        return musicRepository.findMusicDetailById(contentId)
                .orElseThrow(() -> new BusinessException("음악을 찾을 수 없습니다. id: " + contentId));
    }
}
