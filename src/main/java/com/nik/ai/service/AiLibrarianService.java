package com.nik.ai.service;

import com.nik.ai.domain.AiChatMessageRole;
import com.nik.ai.model.AiChatMessage;
import com.nik.ai.model.AiChatSession;
import com.nik.model.User;
import com.nik.ai.dto.AiChatMessageDTO;
import com.nik.ai.dto.AiChatSessionDTO;
import com.nik.ai.request.AiLibrarianChatRequest;
import com.nik.ai.response.AiLibrarianChatResponse;
import com.nik.ai.repository.AiChatSessionRepository;
import com.nik.ai.repository.AiChatMessageRepository;
import com.nik.exception.AiServiceException;
import com.nik.exception.AuthenticationFailureException;
import com.nik.exception.ResourceNotFoundException;
import com.nik.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${spring.ai.openai.api-key:}')")
public class AiLibrarianService {

    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final UserService userService;

    @Qualifier("aiLibrarianChatClient")
    private final ChatClient aiLibrarianChatClient;

    @Transactional
    public AiLibrarianChatResponse chat(AiLibrarianChatRequest request) {
        User currentUser = getCurrentUserOrThrow();
        AiChatSession session = resolveSession(currentUser, request.getSessionId(), request.getMessage());
        String normalizedMessage = summarizeForLog(request.getMessage());

        log.info("AI Librarian request received - userId={}, sessionId={}, message=\"{}\"",
                currentUser.getId(), session.getId(), normalizedMessage);

        createMessage(session, AiChatMessageRole.USER, request.getMessage());

        String answer = generateAssistantAnswer(currentUser, session, request.getMessage());

        if (!StringUtils.hasText(answer)) {
            answer = "I could not generate a useful answer right now. Please try rephrasing your question.";
        }

        log.info("AI Librarian response generated - userId={}, sessionId={}, chars={}",
                currentUser.getId(), session.getId(), answer.length());

        AiChatMessage assistantMessage = createMessage(session, AiChatMessageRole.ASSISTANT, answer);
        touchSession(session, request.getMessage());

        return new AiLibrarianChatResponse(
                session.getId(),
                answer,
                toMessageDTO(assistantMessage),
                toSessionDTO(session)
        );
    }

    @Transactional
    public AiChatSessionDTO createSession() {
        User currentUser = getCurrentUserOrThrow();

        AiChatSession session = new AiChatSession();
        session.setId(UUID.randomUUID().toString());
        session.setUser(currentUser);
        session.setTitle("New AI Librarian Chat");
        session.setLastMessageAt(LocalDateTime.now());
        return toSessionDTO(aiChatSessionRepository.save(session));
    }

    @Transactional(readOnly = true)
    public AiChatSessionDTO getCurrentSession() {
        User currentUser = getCurrentUserOrThrow();
        return aiChatSessionRepository.findTopByUserIdOrderByUpdatedAtDesc(currentUser.getId())
                .map(this::toSessionDTO)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public AiChatSessionDTO getSession(String sessionId) {
        User currentUser = getCurrentUserOrThrow();
        AiChatSession session = aiChatSessionRepository.findByIdAndUserId(sessionId, currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Chat session not found"));
        return toSessionDTO(session);
    }

    private AiChatSession resolveSession(User currentUser, String requestedSessionId, String firstMessage) {
        if (StringUtils.hasText(requestedSessionId)) {
            return aiChatSessionRepository.findByIdAndUserId(requestedSessionId, currentUser.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Chat session not found"));
        }

        AiChatSession session = new AiChatSession();
        session.setId(UUID.randomUUID().toString());
        session.setUser(currentUser);
        session.setTitle(buildTitle(firstMessage));
        session.setLastMessageAt(LocalDateTime.now());
        return aiChatSessionRepository.save(session);
    }

    private void touchSession(AiChatSession session, String latestUserMessage) {
        session.setLastMessageAt(LocalDateTime.now());
        if (!StringUtils.hasText(session.getTitle()) || "New AI Librarian Chat".equals(session.getTitle())) {
            session.setTitle(buildTitle(latestUserMessage));
        }
        aiChatSessionRepository.save(session);
    }

    private AiChatMessage createMessage(AiChatSession session, AiChatMessageRole role, String content) {
        AiChatMessage message = new AiChatMessage();
        message.setSession(session);
        message.setRole(role);
        message.setContent(content);
        return aiChatMessageRepository.save(message);
    }

    private AiChatSessionDTO toSessionDTO(AiChatSession session) {
        List<AiChatMessageDTO> messages = aiChatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())
                .stream()
                .map(this::toMessageDTO)
                .toList();

        return new AiChatSessionDTO(
                session.getId(),
                session.getTitle(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getLastMessageAt(),
                messages
        );
    }

    private AiChatMessageDTO toMessageDTO(AiChatMessage message) {
        return new AiChatMessageDTO(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getCreatedAt()
        );
    }

    private String buildTitle(String message) {
        if (!StringUtils.hasText(message)) {
            return "New AI Librarian Chat";
        }
        String normalized = message.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 60 ? normalized : normalized.substring(0, 57) + "...";
    }

    private String summarizeForLog(String message) {
        if (!StringUtils.hasText(message)) {
            return "";
        }
        String normalized = message.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "...";
    }

    private String generateAssistantAnswer(User currentUser, AiChatSession session, String message) {
        try {
            return aiLibrarianChatClient.prompt()
                    .user(message)
                    .advisors(advisorSpec -> advisorSpec.param(CONVERSATION_ID, session.getId()))
                    .toolContext(Map.of(
                            "userId", currentUser.getId(),
                            "username", currentUser.getEmail(),
                            "sessionId", session.getId()
                    ))
                    .call()
                    .content();
        } catch (Exception ex) {
            throw new AiServiceException("AI Librarian is temporarily unavailable. Please try again in a moment.", ex);
        }
    }

    private User getCurrentUserOrThrow() {
        try {
            return userService.getCurrentUser();
        } catch (Exception ex) {
            throw new AuthenticationFailureException("Unable to resolve current user for AI Librarian", ex);
        }
    }
}










