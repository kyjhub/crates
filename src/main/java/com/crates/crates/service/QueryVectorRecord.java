package com.crates.crates.service;

public record QueryVectorRecord(Long queryId, String queryText, float[] vector) {
}
