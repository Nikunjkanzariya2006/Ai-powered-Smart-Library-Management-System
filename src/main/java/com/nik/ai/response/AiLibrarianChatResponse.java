package com.nik.ai.response;

import com.nik.ai.dto.AiChatMessageDTO;
import com.nik.ai.dto.AiChatSessionDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiLibrarianChatResponse {

    private String sessionId;
    private String answer;
    private AiChatMessageDTO assistantMessage;
    private AiChatSessionDTO session;
}



