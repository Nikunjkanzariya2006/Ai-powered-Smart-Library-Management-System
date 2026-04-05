package com.nik.ai.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AiLibrarianChatRequest {

    private String sessionId;

    @NotBlank(message = "Message is required")
    private String message;
}

