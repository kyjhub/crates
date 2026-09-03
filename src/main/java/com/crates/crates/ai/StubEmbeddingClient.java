package com.crates.crates.ai;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * AI 서버와 통신할 수 없는 동안 임베딩을 대신 만들어주는 구현체.
 *
 * <p>같은 검색어에는 항상 같은 벡터가 나오므로 검색 결과 보드도 검색어마다 고정된다.
 * ContentVectorSeeder가 심어둔 더미 콘텐츠 벡터와 같은 규칙(float32, L2 정규화)을 따르기 때문에
 * Qdrant Cosine 검색에 그대로 넣을 수 있다.</p>
 *
 * <p>다만 콘텐츠 벡터도 더미라서, 검색 결과가 <b>의미상</b> 검색어와 가깝지는 않다.
 * 화면과 API 흐름을 끝까지 굴려보기 위한 대체물이고, AI 서버가 열리면
 * {@code ai.server.stub.enabled}를 false로 되돌리기만 하면 된다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.server.stub.enabled", havingValue = "true")
public class StubEmbeddingClient implements EmbeddingClient {

    @Value("${ai.server.embedding-dimension}")
    private int embeddingDimension;

    @Value("${ai.server.stub.random-seed}")
    private long randomSeed;

    @PostConstruct
    void warnStubEnabled()
    {
        log.warn("AI 서버 스텁이 켜져 있습니다. 임베딩은 실제 값이 아니라 재현 가능한 더미 벡터입니다. "
                + "(dimension: {}) 실제 통신이 가능해지면 ai.server.stub.enabled를 false로 되돌리세요.", embeddingDimension);
    }

    @Override
    public float[] embed(String text)
    {
        long seed = randomSeed ^ DeterministicVectorFactory.mix64(hash(text));
        log.debug("[스텁 임베딩] text: {} / seed: {}", text, seed);

        return DeterministicVectorFactory.create(seed, embeddingDimension);
    }

    /**
     * String.hashCode()는 32비트라 검색어가 쌓이면 충돌이 눈에 띈다.
     * 서로 다른 검색어가 같은 보드를 받지 않도록 64비트 FNV-1a로 해싱한다.
     */
    private long hash(String text)
    {
        long hash = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8))
        {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
