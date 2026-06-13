package com.crates.crates.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;

import java.util.List;

@Data
@AllArgsConstructor
public class ContentQueryDto
{
    private Long id;
    private String title;
    private String s3ObjectKey;
    private String imageExtension;
    private String contentType; // DTYPE (Discriminator Value)
    private LocalDate releaseDate;
    private List<String> genre;
}
