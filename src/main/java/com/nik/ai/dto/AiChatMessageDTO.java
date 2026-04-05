package com.nik.ai.dto;

import com.nik.ai.domain.AiChatMessageRole;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiChatMessageDTO {

    private Long id;
    private AiChatMessageRole role;
    private String content;
    private LocalDateTime createdAt;
}


