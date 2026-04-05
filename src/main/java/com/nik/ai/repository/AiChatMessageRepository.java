package com.nik.ai.repository;

import com.nik.ai.model.AiChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

    List<AiChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    @Modifying
    @Query("DELETE FROM AiChatMessage m WHERE m.session.id IN :sessionIds")
    void deleteBySessionIds(@Param("sessionIds") List<String> sessionIds);
}



