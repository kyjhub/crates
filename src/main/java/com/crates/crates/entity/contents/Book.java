package com.crates.crates.entity.contents;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@DiscriminatorValue(Book.DTYPE)
@PrimaryKeyJoinColumn(name = "content_id")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Book extends Content {

    public static final String DTYPE = "BOOK";

    private String author;
    private String publisher;
    private String isbn;

    @Column(columnDefinition = "TEXT")
    private String plot;
}