package com.crates.crates.ai;

import com.crates.crates.Global.exception.AiServerException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
public class AiEmbeddingClient {

    @Qualifier("aiServerRestClient")
    private final RestClient aiServerRestClient;

    @Value("${ai.server.embedding-path}")
    private String embeddingPath;

    public float[] embed(String text) {
        EmbeddingResponse response;
        try {
            response = aiServerRestClient.post()
                    .uri(embeddingPath)
                    .body(new EmbeddingRequest(text))
                    .retrieve()
                    .body(EmbeddingResponse.class);
        } catch (RestClientException e) {
            throw new AiServerException("AI 서버 통신에 실패했습니다.", e);
        }

        if (response == null || response.vector() == null) {
            throw new AiServerException("AI 서버가 빈 응답을 반환했습니다.");
        }
        return response.vector();
    }
}
