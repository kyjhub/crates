package com.crates.crates.service;

/**
 * @param queryText    보드 제목
 * @param modelVersion 벡터를 만든 AI 모델 체크포인트. 쓸 때만 필요하다.
 */
public record QueryVectorRecord(Long queryId, String queryText, float[] vector, String modelVersion) {
}
