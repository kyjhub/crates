package com.crates.crates.repository;


import com.crates.crates.entity.UserRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRefreshTokenRepository extends JpaRepository<UserRefreshToken, Long> {

    List<UserRefreshToken> findByUserIdOrderByExpiresAtAsc(Long userId);

    Optional<UserRefreshToken> findByJti(String jti);

    void deleteByJti(String jti);

    void deleteByUserId(Long userId);
}
