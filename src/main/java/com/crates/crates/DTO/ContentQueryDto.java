package com.crates.crates.DTO;

import java.time.Year;

public record ContentQueryDto(
        Long id,
        String title,
        String s3ObjectKey,
        String imageExtension,
        String contentType, // DTYPE (Discriminator Value)
        Year releaseYear
) {
}
