package com.crates.crates.repository;

import com.crates.crates.entity.user.UserVector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserVectorRepository extends JpaRepository<UserVector, Long> {
}
