package com.crates.crates.repository;


import com.crates.crates.entity.UserRefreshToken;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRefreshTokenRepository extends JpaRepository<UserRefreshToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    List<UserRefreshToken> findByUserIdOrderByExpiresAtAsc(Long userId);

    Optional<UserRefreshToken> findByJti(String jti);

    void deleteByJti(String jti);

    void deleteByUserId(Long userId);

    void deleteByExpiresAtBefore(LocalDateTime time);
}
