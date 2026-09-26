package com.crates.crates.entity.board;

import com.crates.crates.entity.contents.Content;
import jakarta.persistence.*;
import lombok.*;

// 2. 매핑 엔티티 정의
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class BoardItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id")
    @ToString.Exclude       // 무한루프 방지
    private Board board;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_id")
    private Content content;

    /**
     * 보드 안에서의 배치 위치(1~8). 화면은 2행 4열로 렌더링된다.
     * <pre>
     *   1 2 3 4
     *   5 6 7 8
     * </pre>
     *
     * <p>사용자가 직접 정할 수 있는 것은 USER_CUSTOM 보드뿐이고, AI_RECOMMEND는 생성 시점에
     * 유사도 순으로 자동으로 채운다.
     * nullable로 두면 조회 시 정렬 기준이 사라져 순서가 매번 달라지므로 NOT NULL이다.</p>
     */
    @Column(nullable = false)
    private Integer slotNo;

}