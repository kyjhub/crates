package com.crates.crates.ai;

/**
 * 텍스트를 임베딩 벡터로 변환한다.
 *
 * <p>구현체는 둘이다. 실제 AI 서버를 호출하는 {@link AiEmbeddingClient}와,
 * AI 서버와 통신할 수 없는 동안 대신 쓰는 {@link StubEmbeddingClient}.
 * {@code ai.server.stub.enabled} 값에 따라 둘 중 하나만 빈으로 등록되므로
 * 사용하는 쪽은 어느 구현체가 붙었는지 알 필요가 없다.</p>
 */
public interface EmbeddingClient {

    /**
     * @return 길이가 {@code ai.server.embedding-dimension}인 float32 벡터
     */
    float[] embed(String text);
}
