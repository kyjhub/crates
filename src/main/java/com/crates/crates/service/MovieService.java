package com.crates.crates.service;

import com.crates.crates.DTO.MovieResponseDTO;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MovieService {

    private final MovieRepository movieRepository;

    public MovieResponseDTO getDetail(Long contentId) {
        return movieRepository.findMovieDetailById(contentId)
                .orElseThrow(() -> new BusinessException("영화를 찾을 수 없습니다. id: " + contentId));
    }
}
