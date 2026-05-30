package com.crates.crates.entity.contents;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
@DiscriminatorValue("MOVIE")        // content의 자식 엔티티
@PrimaryKeyJoinColumn(name = "content_id")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Movie extends Content {

    private Integer runningTime;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> director;  // 감독이 여러명인 경우도 있음

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> actor;

    @Column(columnDefinition = "TEXT")
    private String plot;
}