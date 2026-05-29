package com.crates.crates.DTO;

import java.util.List;
import java.util.Map;

public class QdrantDto {
    // 아래  코드들은 각각 record식 생성자
    // 만약 값을 검증하고 싶다면 중괄호 안에 다음과 같이 코드를 추가할 수 있습니다.

    // 1. Qdrant에 데이터를 넣을 때 (Upsert Request)
    public record UpsertRequest(List<Point> points) {}

    public record Point(Long id, List<Float> vector, Map<String, Object> payload) {}

    // 2. Qdrant에서 검색할 때 (Search Request)
    public record SearchRequest(List<Float> vector, int limit, boolean with_payload, boolean with_vector) {}

    // 3. Qdrant의 응답을 받을 때 (Search Response)
    public record SearchResponse(List<SearchResult> result, String status, double time) {}

    public record SearchResult(Long id, double score, Map<String, Object> payload, List<Float> vector) {}
}
