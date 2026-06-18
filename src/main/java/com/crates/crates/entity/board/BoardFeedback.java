package com.crates.crates.entity.board;

import com.crates.crates.entity.user.User;
import com.crates.crates.enumData.Rating;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
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
