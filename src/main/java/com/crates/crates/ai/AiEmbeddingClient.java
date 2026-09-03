package com.crates.crates.ai;

import com.crates.crates.Global.exception.AiServerException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// 스텁이 켜져 있으면 이 빈은 올라오지 않는다. 둘 다 등록되면 EmbeddingClient 주입이 모호해진다.
@Component
@ConditionalOnProperty(name = "ai.server.stub.enabled", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class AiEmbeddingClient implements EmbeddingClient {

    @Qualifier("aiServerRestClient")
    private final RestClient aiServerRestClient;

    @Value("${ai.server.embedding-path}")
    private String embeddingPath;

    @Value("${ai.server.embedding-dimension}")
    private int embeddingDimension;

    @Override
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

        float[] vector = response.vector();
        if (vector.length != embeddingDimension) {
            throw new AiServerException(
                    "AI 서버 응답 벡터 차원이 올바르지 않습니다. 예상: %d, 실제: %d".formatted(embeddingDimension, vector.length));
        }
        return vector;
    }
}
