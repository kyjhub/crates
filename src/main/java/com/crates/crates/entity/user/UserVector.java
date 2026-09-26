package com.crates.crates.entity.user;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 사용자 취향 벡터. 가입 직후 콘텐츠를 고르면 그때 행이 생긴다 — 고르기 전에는 행이 없다.
 *
 * <p>벡터는 {@code userVector} 한 칸이다. 좋아요가 바뀔 때마다 좋아요한 보드들과 가입 때 고른
 * 콘텐츠({@code initialContentIds})로 처음부터 다시 계산한다. 가입 때 고른 것은 다른 곳에 남지 않으므로
 * id를 함께 저장한다(V2__UserSchema).</p>
 */
@Entity
@Table(name = "user_vector")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserVector {

    @Id
    private Long userId;

    // 식별 관계 매핑: User의 PK를 UserVector의 PK 겸 FK로 사용
    // @MapsId를 쓸려면 참조해야만 함
    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "real[]", nullable = false)
    private float[] userVector;

    /** 가입 때 고른 콘텐츠 id. 한 번 쓰고 바꾸지 않는다. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "bigint[]", nullable = false, updatable = false)
    private List<Long> initialContentIds;

    private LocalDateTime updatedAt;

    // @MapsId로 userId를 user의 fk를 pk로 쓰기 때문에 userId는 builder에 포함되면 안된다.
    @Builder
    private UserVector(User user, float[] userVector, List<Long> initialContentIds, LocalDateTime updatedAt) {
        this.user = user;
        this.userVector = userVector;
        this.initialContentIds = List.copyOf(initialContentIds);
        this.updatedAt = updatedAt;
    }

    /**
     * 취향 벡터 갱신. 좋아요한 보드들과 가입 때 고른 콘텐츠로 다시 계산한 값으로 통째로 바꾼다.
     * 증분 누적이 아니라 재계산이므로 이전 값과의 연속성을 신경 쓸 필요가 없다.
     */
    public void updateVector(float[] userVector, LocalDateTime updatedAt) {
        this.userVector = userVector;
        this.updatedAt = updatedAt;
    }
}
