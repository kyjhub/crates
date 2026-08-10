package com.crates.crates.service;

import com.crates.crates.ai.AiEmbeddingClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SearchService {

    private final AiEmbeddingClient aiEmbeddingClient;

    public float[] getQueryVector(String keyword) {
        return aiEmbeddingClient.embed(keyword);
    }
}
