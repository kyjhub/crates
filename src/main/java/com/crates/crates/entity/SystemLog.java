package com.crates.crates.entity;

import com.crates.crates.enumData.EventType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "system_log") // 예약어 회피
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime timestamp;

    // 로그는 유저가 탈퇴해도 남아야 하므로 객체 연관관계(ManyToOne) 대신 ID만 들고 있는 것이 안전합니다.
    private Long userId;

    @Enumerated(EnumType.STRING)
    private EventType eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload; // 추후 Map<String, Object> 나 별도 DTO로 파싱할 수 있습니다.
}
