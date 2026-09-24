package com.crates.crates.DTO;

/** 원본 id(source_key)와 content.id의 짝. 벡터 CSV·AI 서버의 id를 Qdrant point id로 바꿀 때 쓴다. */
public record ContentSourceKeyDto(Long id, String sourceKey) {
}
