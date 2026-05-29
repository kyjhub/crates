package com.crates.crates.entity;

import com.crates.crates.entity.Board.BoardFeedback;
import com.crates.crates.enumData.Gender;
import com.crates.crates.enumData.ROLE;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users") // PostgreSQL 예약어 회피
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String pwd;

    private ROLE role;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private LocalDate birthYear;

    private String nickname;

    // 양방향 매핑 ( User -> boardFeedback)
    @OneToMany(mappedBy = "user", cascade =   CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    @Builder.Default
    private List<BoardFeedback>  boardFeedbacks = new ArrayList<>();

    // userVector는 Service 계층에서 repository로 조회하는걸로, 굳이 양방향 매핑할 필요 없다.
}
