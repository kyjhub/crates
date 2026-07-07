package com.crates.crates.qdrant;

import java.util.Map;

public record ScoredPointResult(long id, float score, Map<String, Object> payload) {
}
