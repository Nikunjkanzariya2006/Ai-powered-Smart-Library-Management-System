package com.nik.ai.controller;

import com.nik.ai.service.AiBookReviewSummaryService;
import com.nik.exception.BookException;
import com.nik.payload.response.ApiResponse;
import com.nik.payload.response.BookReviewAiSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class AiBookReviewSummaryController {

    private final AiBookReviewSummaryService aiBookReviewSummaryService;

    @GetMapping("/book/{bookId}/ai-summary")
    public ResponseEntity<?> getAiReviewSummary(@PathVariable Long bookId) {
        try {
            BookReviewAiSummaryResponse summary = aiBookReviewSummaryService.generateSummary(bookId);
            return ResponseEntity.ok(summary);
        } catch (BookException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiResponse(e.getMessage(), false));
        }
    }
}
