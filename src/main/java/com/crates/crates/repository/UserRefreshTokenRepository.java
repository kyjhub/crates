package com.crates.crates.repository;


import com.crates.crates.entity.UserRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface UserRefreshTokenRepository extends JpaRepository<UserRefreshToken, Long> {

    List<UserRefreshToken> findByUserIdOrderByExpiresAtAsc(Long userId);

    Optional<UserRefreshToken> findByTokenValue(String tokenValue);

    void deleteByTokenValue(String tokenValue);

    void deleteByUserId(Long userId);
}
