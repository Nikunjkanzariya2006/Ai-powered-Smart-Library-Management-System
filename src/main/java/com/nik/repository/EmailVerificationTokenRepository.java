package com.nik.repository;

import com.nik.model.EmailVerificationToken;
import com.nik.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);
    Optional<EmailVerificationToken> findByUser(User user);
    void deleteByUser(User user);
    void deleteAllByExpiryDateBefore(LocalDateTime dateTime);
}

