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
public class MovieResponseDTO implements ContentDetailResponse {
    private Integer runningTime;
    private List<String> director;
    private List<String> actor;
    private String plot;
}
