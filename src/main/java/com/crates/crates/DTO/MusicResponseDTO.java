package com.crates.crates.DTO;


import com.crates.crates.entity.Contents.Content;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
public class MusicResponseDTO extends ContentResponseDto {

    private List<String> artist;
    private String plot;

}
