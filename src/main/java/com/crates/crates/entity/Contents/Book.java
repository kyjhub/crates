package com.crates.crates.entity.Contents;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@DiscriminatorValue("BOOK")
@PrimaryKeyJoinColumn(name = "content_id")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Book extends Content {

    private String author;
    private String publisher;
    private String isbn;

    @Column(columnDefinition = "TEXT")
    private String plot;
}