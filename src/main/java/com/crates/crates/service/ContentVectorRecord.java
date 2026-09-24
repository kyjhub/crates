package com.crates.crates.service;

/**
 * @param modelVersion 벡터를 만든 AI 모델 체크포인트 (예: {@code trained_model.pt@1786697109}).
 *                     검색어·취향 벡터와 같은 모델 공간인지 확인하는 근거라 비워둘 수 없다.
 */
public record ContentVectorRecord(Long contentId, float[] vector, String modelVersion) {
}
