package com.nik.ai.config;

import com.nik.ai.advisors.TokenUsageAuditAdvisor;
import com.nik.ai.tools.AiLibrarianTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.util.List;

@Configuration
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${spring.ai.openai.api-key:}')")
public class AiLibrarianChatClientConfig {

    @Value("classpath:/ai/prompts/aiLibrarianSystemPrompt.st")
    private Resource aiLibrarianSystemPrompt;

    @Bean
    ChatMemory aiLibrarianChatMemory(JdbcChatMemoryRepository jdbcChatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(10)
                .build();
    }

    @Bean("aiLibrarianChatClient")
    ChatClient aiLibrarianChatClient(ChatClient.Builder chatClientBuilder,
                                     ChatMemory aiLibrarianChatMemory,
                                     AiLibrarianTools aiLibrarianTools) {
        Advisor memoryAdvisor = MessageChatMemoryAdvisor.builder(aiLibrarianChatMemory).build();
        Advisor tokenUsageAdvisor = new TokenUsageAuditAdvisor();

        return chatClientBuilder
                .defaultSystem(aiLibrarianSystemPrompt)
                .defaultTools(aiLibrarianTools)
                .defaultAdvisors(List.of(memoryAdvisor, tokenUsageAdvisor))
                .build();
    }

    @Bean("bookReviewSummaryChatClient")
    ChatClient bookReviewSummaryChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }

    @Bean("recommendationChatClient")
    ChatClient recommendationChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }
}

