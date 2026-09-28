package com.crates.crates.repository;

import com.crates.crates.entity.contents.OnboardingContent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface OnboardingContentRepository extends JpaRepository<OnboardingContent, Long> {

    /**
     * 한 종류의 후보 콘텐츠 id를 인기순으로 돌려준다.
     * 요약 DTO 조립(이미지 URL, 순서 보존)은 ContentService.getContentSummaries가 한다.
     */
    @Query("SELECT o.contentId FROM OnboardingContent o " +
            "WHERE o.content.dtype = :dtype ORDER BY o.popularityRank")
    List<Long> findContentIdsByDtype(@Param("dtype") String dtype);

    /** 고른 콘텐츠 중 후보 목록에 있는 것의 수. 입력과 다르면 목록 밖의 콘텐츠가 섞인 것이다. */
    long countByContentIdIn(Collection<Long> contentIds);
}
