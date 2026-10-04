package com.crates.crates.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 추천·검색 보드의 제목을 정한다. (docs/board-schema.md 4장)
 *
 * <p>보드에 담긴 콘텐츠의 평균 벡터와 가장 가까운 query가 제목이 된다. 제목은 이 방식으로만 정한다 —
 * 대신 쓸 고정 제목이 없다. 그래서 query를 찾지 못하면 빈 제목으로 내보내지 않고 오류로 끝낸다.</p>
 *
 * <p>query와 query_vector는 AI 개발자가 Qdrant에 적재한다. 실제 데이터가 들어오기 전까지는
 * QueryVectorStubLoader가 콘텐츠 벡터를 복제한 임시 query를 넣는다.</p>
 *
 * <p>트랜잭션 밖에서 부른다. Qdrant 검색을 기다리는 동안 DB 커넥션을 쥐지 않기 위해서다
 * (BoardService.findSavedGeneratedBoard 참고).</p>
 */
@Service
@RequiredArgsConstructor
public class BoardTitleService {

    private final QueryVectorService queryVectorService;

    /**
     * 콘텐츠 벡터들의 평균과 가장 가까운 query.
     *
     * <p>평균을 정규화하지 않는다. Cosine 거리는 크기를 보지 않으므로 방향만 맞으면 된다.</p>
     *
     * @throws IllegalStateException 벡터가 없거나 query_vector에서 아무것도 찾지 못했을 때.
     *         정상이라면 일어나지 않는다 — 콘텐츠 벡터나 query_vector가 비어 있다는 뜻이다(Qdrant 재적재 중 등).
     */
    public String titleFor(List<float[]> contentVectors)
    {
        if (contentVectors.isEmpty())
        {
            throw new IllegalStateException("보드 제목을 정할 콘텐츠 벡터가 없습니다. content_vector 적재 상태를 확인하세요.");
        }

        return queryVectorService.findMostSimilarQuery(mean(contentVectors))
                .map(QueryMatch::queryText)
                .orElseThrow(() -> new IllegalStateException(
                        "보드 제목으로 쓸 query를 찾지 못했습니다. query_vector 적재 상태를 확인하세요."));
    }

    private float[] mean(List<float[]> vectors)
    {
        float[] sum = new float[vectors.getFirst().length];
        for (float[] vector : vectors)
        {
            for (int i = 0; i < sum.length; i++)
            {
                sum[i] += vector[i];
            }
        }
        for (int i = 0; i < sum.length; i++)
        {
            sum[i] /= vectors.size();
        }
        return sum;
    }
}
