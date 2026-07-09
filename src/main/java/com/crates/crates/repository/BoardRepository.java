package com.crates.crates.repository;

import com.crates.crates.entity.board.Board;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BoardRepository extends JpaRepository<Board, Long> {
    List<Board> findByDeletedAtIsNullOrderByLikeCountDesc(Pageable pageable);
}
