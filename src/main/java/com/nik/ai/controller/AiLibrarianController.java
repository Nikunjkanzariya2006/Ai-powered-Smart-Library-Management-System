package com.nik.ai.controller;

import com.nik.ai.service.AiLibrarianService;
import com.nik.ai.dto.AiChatSessionDTO;
import com.nik.ai.request.AiLibrarianChatRequest;
import com.nik.ai.response.AiLibrarianChatResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-librarian")
@RequiredArgsConstructor
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${spring.ai.openai.api-key:}')")
public class AiLibrarianController {

    private final AiLibrarianService aiLibrarianService;

    @PostMapping("/chat")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<AiLibrarianChatResponse> chat(@Valid @RequestBody AiLibrarianChatRequest request) {
        return ResponseEntity.ok(aiLibrarianService.chat(request));
    }

    @PostMapping("/sessions")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<AiChatSessionDTO> createSession() {
        return ResponseEntity.ok(aiLibrarianService.createSession());
    }

    @GetMapping("/sessions/current")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<AiChatSessionDTO> getCurrentSession() {
        return ResponseEntity.ok(aiLibrarianService.getCurrentSession());
    }
}



