package com.crates.crates.entity.user;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_vector")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
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
    @Column(columnDefinition = "real[]")
    private float[] userVector;

    private LocalDateTime updatedAt;

    // @MapsId로 userId를 user의 fk를 pk로 쓰기 때문에 userId는 builder에 포함되면 안된다.
    @Builder
    private UserVector(User user, float[] userVector, LocalDateTime updatedAt) {
        this.user = user;
        this.userVector = userVector;
        this.updatedAt = updatedAt;
    }
}