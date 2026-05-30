package com.crates.crates.entity.board;

import com.crates.crates.entity.contents.Content;
import com.crates.crates.entity.user.User;
import com.crates.crates.enumData.BoardType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter // 무한루프를 방지하기 위해
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@EqualsAndHashCode(of = "id")
public class Board {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // PRE_MADE의 경우 null 허용
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private User member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "query_id")
    private BoardQuery query;

    @Enumerated(EnumType.STRING)
    private BoardType boardType;

    private LocalDateTime deletedAt; // 소프트 삭제(Soft Delete)용

    private Long likeCount;

    // 🌟 양방향 매핑 (Board -> BoardItem)
    @OneToMany(
            mappedBy = "board",          // 1. "BoardItem 엔티티의 'board' 필드가 관계의 주인이다"라고 선언, 누가 외래키의 주인인지 명시
            cascade = CascadeType.ALL,   // 2. Board를 저장/삭제할 때 BoardItem도 함께 저장/삭제됨
            orphanRemoval = true         // 3. 리스트에서 BoardItem을 빼기만 해도 DB에서 DELETE 쿼리가 날아감
    )
    @ToString.Exclude   // 무한루프 방지
    @Builder.Default    // @Builder가 리스트 초기화 식을 무시하는 것을 방지하기 위해
    private List<BoardItem> boardItems = new ArrayList<>(); // NullPointerException 방지를 위한 초기화


    // 🌟 시니어 추천: 연관관계 편의 메서드 (Level 2)
    public void addContent(Content content)
    {
        BoardItem newItem = BoardItem.builder()
                .board(this)       // 연관관계 주인(외래키) 세팅
                .content(content)  // 목적지 컨텐츠 세팅
                .build();

        // 2. 내 리스트에 추가합니다.
        this.boardItems.add(newItem);
    }

    public void removeContent(Content content)
    {
        // 리스트에서 삭제만 해도 orphanRemoval=true 덕분에 DB 매핑 테이블에서 삭제됨
        this.boardItems.removeIf(item -> item.getContent().equals(content));
    }
}