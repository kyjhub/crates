package com.crates.crates.qdrant;

import java.util.Map;

/** vector는 검색할 때 벡터를 요청한 경우에만 채워지고, 아니면 빈 배열이다. */
public record ScoredPointResult(long id, float score, Map<String, Object> payload, float[] vector) {
}
