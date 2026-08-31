package com.crates.crates.entity.board;

import com.crates.crates.entity.user.User;
import com.crates.crates.enumData.Rating;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
// 한 사용자가 같은 보드에 피드백을 두 번 남길 수 없게 DB에서 막는다.
// 이 제약이 없으면 좋아요를 연타할 때 board.likeCount가 실제보다 부풀어 오른다.
@Table(
        name = "board_feedback",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_board_feedback_board_user",
                columnNames = {"board_id", "user_id"}
        )
)
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(of = "id")
public class BoardFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id")
    @ToString.Exclude   // 무한루프 방지
    private Board board;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = true, foreignKey = @ForeignKey(name = "fk_feedback_user"))
    @ToString.Exclude   // 무한루프 방지
    private User user;

    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    private Rating rating;
}
