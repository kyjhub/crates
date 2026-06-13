package com.crates.crates.repository;

import com.crates.crates.DTO.MusicResponseDTO;
import com.crates.crates.entity.contents.Music;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MusicRepository extends JpaRepository<Music, Long> {
    @Query("SELECT new com.crates.crates.DTO.MusicResponseDTO(m.artist, m.plot) " +
           "FROM Music m WHERE m.id = :id")
    Optional<MusicResponseDTO> findMusicDetailById(@Param("id") Long id);
}
