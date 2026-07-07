package com.crates.crates.qdrant;

import java.util.Map;

public record PointRecord(long id, float[] vector, Map<String, Object> payload) {
}
