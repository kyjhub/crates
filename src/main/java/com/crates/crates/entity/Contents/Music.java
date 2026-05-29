package com.crates.crates.entity.Contents;

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
@DiscriminatorValue("MUSIC")
@PrimaryKeyJoinColumn(name = "content_id")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Music extends Content {

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> artist;

    @Column(columnDefinition = "TEXT")
    private String plot;
}