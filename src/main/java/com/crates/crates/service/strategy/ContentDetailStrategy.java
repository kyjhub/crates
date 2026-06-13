package com.crates.crates.service.strategy;

import com.crates.crates.DTO.ContentDetailResponse;

public interface ContentDetailStrategy {
    String getSupportedType();
    ContentDetailResponse getDetail(Long contentId);
}
