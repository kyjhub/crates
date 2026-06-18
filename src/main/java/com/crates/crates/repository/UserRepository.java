package com.crates.crates.repository;

import com.crates.crates.entity.user.User;
import com.crates.crates.enumData.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByIdAndDeletedAtIsNull(Long id);
    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);
    Optional<User> findByLoginId(String loginId);
    Optional<User> findByEmail(String email);
    Boolean existsByLoginId(String loginId);
    Boolean existsByEmail(String email);
    Boolean existsByNickname(String nickname);
}
