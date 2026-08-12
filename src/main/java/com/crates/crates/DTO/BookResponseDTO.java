package com.crates.crates.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookResponseDTO implements ContentDetailResponse {
    private String author;
    private String publisher;
    private String plot;
}
