package com.crates.crates.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MusicResponseDTO implements ContentDetailResponse {
    private List<String> artist;
    private String plot;
}
