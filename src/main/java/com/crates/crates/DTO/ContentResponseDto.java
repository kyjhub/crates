package com.crates.crates.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Year;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentResponseDto {
    private Long id;
    private String title;
    private String imageUrl;
    private String contentType; // DTYPE (Discriminator Value)
    private Year releaseYear;
}
