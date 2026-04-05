package com.nik.repository;


import com.nik.model.PasswordResetToken;
import com.nik.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByToken(String token);
    Optional<PasswordResetToken> findByUser(User user);
    void deleteByUser(User user);
    void deleteAllByExpiryDateBefore(LocalDateTime dateTime);
}

