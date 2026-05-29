package com.crates.crates.repository;

import com.crates.crates.entity.Board.BoardFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BoardFeedbackRepository extends JpaRepository<BoardFeedback, Long> {
}
