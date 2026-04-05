package com.nik.ai.service;

import com.nik.exception.BookException;
import com.nik.payload.response.BookReviewAiSummaryResponse;

public interface AiBookReviewSummaryService {

    BookReviewAiSummaryResponse generateSummary(Long bookId) throws BookException;
}

