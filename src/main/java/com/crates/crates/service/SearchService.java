package com.crates.crates.service;

import com.crates.crates.ai.AiEmbeddingClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final AiEmbeddingClient aiEmbeddingClient;

    public float[] getQueryVector(String keyword) {
        float[] vector = aiEmbeddingClient.embed(keyword);
        log.info("[AI 서버 통신 성공] keyword: {} / vector dimension: {}", keyword, vector.length);
        return vector;
    }
}
