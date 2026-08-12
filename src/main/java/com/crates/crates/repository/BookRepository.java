package com.crates.crates.repository;

import com.crates.crates.DTO.BookResponseDTO;
import com.crates.crates.entity.contents.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BookRepository extends JpaRepository<Book, Long> {
    @Query("SELECT new com.crates.crates.DTO.BookResponseDTO(m.author, m.publisher, m.plot) " +
           "FROM Book m WHERE m.id = :id")
    Optional<BookResponseDTO> findBookDetailById(@Param("id") Long id);
}
