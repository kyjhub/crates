package com.crates.crates.service.strategy;

import com.crates.crates.DTO.ContentDetailResponse;
import com.crates.crates.Global.exception.BusinessException;
import com.crates.crates.entity.contents.Movie;
import com.crates.crates.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MovieDetailStrategy implements ContentDetailStrategy {

    private final MovieRepository movieRepository;

    @Override
    public String getSupportedType() {
        return Movie.DTYPE;
    }

    @Override
    public ContentDetailResponse getDetail(Long contentId) {
        return movieRepository.findMovieDetailById(contentId)
                .orElseThrow(() -> new BusinessException("영화를 찾을 수 없습니다. id: " + contentId));
    }
}
