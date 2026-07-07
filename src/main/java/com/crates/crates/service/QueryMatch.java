package com.crates.crates.service;

public record QueryMatch(Long queryId, String queryText, float score) {
}
