package com.crates.crates.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Year;

@Data
@AllArgsConstructor
public class ContentQueryDto
{
    private Long id;
    private String title;
    private String s3ObjectKey;
    private String imageExtension;
    private String contentType; // DTYPE (Discriminator Value)
    private Year releaseYear;
}
