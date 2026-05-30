package com.crates.crates.entity.board;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "board_query") // query는 예약어일 수 있으므로 명확히 지정
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoardQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String query;
}
