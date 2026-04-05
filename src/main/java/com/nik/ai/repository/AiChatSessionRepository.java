package com.nik.ai.repository;

import com.nik.ai.model.AiChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AiChatSessionRepository extends JpaRepository<AiChatSession, String> {

    Optional<AiChatSession> findByIdAndUserId(String id, Long userId);

    Optional<AiChatSession> findTopByUserIdOrderByUpdatedAtDesc(Long userId);

    @Query("SELECT s.id FROM AiChatSession s WHERE s.user.id = :userId")
    List<String> findIdsByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM AiChatSession s WHERE s.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}



