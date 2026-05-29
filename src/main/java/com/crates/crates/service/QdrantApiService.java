package com.crates.crates.service;
import com.crates.crates.DTO.QdrantDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class QdrantApiService {
    // RestAPI 방식

    private final RestClient qdrantRestClient;

    @Value("${ai.vectorstore.qdrant.collection-name}")
    private String collectionName;

    /**
     * 1. 벡터 데이터 저장 (PostgreSQL의 content_id를 PK로 사용)
     */
    public void upsertVector(Long contentId, List<Float> vector, Map<String, Object> payload) {

        // Postman에서 넣었던 JSON 구조를 객체로 조립
        QdrantDto.Point point = new QdrantDto.Point(contentId, vector, payload);
        QdrantDto.UpsertRequest requestBody = new QdrantDto.UpsertRequest(List.of(point));

        // REST API 요청 날리기 (PUT /collections/{name}/points)
        ResponseEntity<String> response = qdrantRestClient.put()
                .uri("/collections/{collectionName}/points", collectionName)
                .body(requestBody)
                .retrieve()
                .toEntity(String.class);

        log.info("Qdrant Upsert 완료. 응답 상태: {}", response.getStatusCode());
    }

    /**
     * 2. 유사도 검색 (Top 10 가져오기)
     */
    public List<QdrantDto.SearchResult> searchSimilarContents(List<Float> targetVector, int limit) {

        // 검색 요청 객체 조립
        QdrantDto.SearchRequest requestBody = new QdrantDto.SearchRequest(
                targetVector, limit, true, false
        );

        // REST API 요청 날리기 (POST /collections/{name}/points/search)
        QdrantDto.SearchResponse response = qdrantRestClient.post()
                .uri("/collections/{collectionName}/points/search", collectionName)
                .body(requestBody)
                .retrieve()
                .body(QdrantDto.SearchResponse.class);

        log.info("Qdrant 검색 완료. 찾은 개수: {}", response.result().size());

        return response.result(); // 매칭된 Top N개의 결과 반환
    }
}