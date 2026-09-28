package com.crates.crates.entity.contents;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 가입 직후 취향 콘텐츠 후보. 사용자는 이 목록에서 1~10개를 골라 첫 취향 벡터를 만든다.
 *
 * <p>콘텐츠 하나는 후보에 한 번만 들어가므로 content_id를 그대로 기본키로 쓴다({@link MapsId}).
 * 행은 시딩(V10__SeedOnboardingContents)만 만든다. 앱은 읽기만 하므로 생성 경로를 두지 않는다.</p>
 */
@Entity
@Table(name = "onboarding_content")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OnboardingContent {

    @Id
    private Long contentId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_id")
    private Content content;

    /** 같은 종류 안에서의 인기 순위(1부터). 화면은 이 순서대로 보여준다. */
    @Column(nullable = false)
    private int popularityRank;
}
