package com.crates.crates.repository;

import com.crates.crates.DTO.MovieResponseDTO;
import com.crates.crates.entity.contents.Movie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MovieRepository extends JpaRepository<Movie, Long> {
    @Query("SELECT new com.crates.crates.DTO.MovieResponseDTO(m.runningTime, m.director, m.actor, m.plot) " +
           "FROM Movie m WHERE m.id = :id")
    Optional<MovieResponseDTO> findMovieDetailById(@Param("id") Long id);
}
