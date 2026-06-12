package com.nik.ai.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.nik.exception.BookException;
import com.nik.model.Book;
import com.nik.model.BookReview;
import com.nik.payload.dto.BookRatingStatisticsDTO;
import com.nik.payload.response.BookReviewAiSummaryResponse;
import com.nik.repository.BookRepository;
import com.nik.repository.BookReviewRepository;
import com.nik.ai.service.AiBookReviewSummaryService;
import com.nik.service.BookReviewService;

@Service
public class AiBookReviewSummaryServiceImpl implements AiBookReviewSummaryService {

    private static final int REVIEW_POOL_SIZE = 24;
    private static final int REVIEW_SAMPLE_SIZE = 6;
    private static final int REVIEW_TEXT_MAX_LENGTH = 220;

    private final BookRepository bookRepository;
    private final BookReviewRepository bookReviewRepository;
    private final BookReviewService bookReviewService;
    private final ChatClient bookReviewSummaryChatClient;

    public AiBookReviewSummaryServiceImpl(
            BookRepository bookRepository,
            BookReviewRepository bookReviewRepository,
            BookReviewService bookReviewService,
            @Qualifier("bookReviewSummaryChatClient") ChatClient bookReviewSummaryChatClient) {
        this.bookRepository = bookRepository;
        this.bookReviewRepository = bookReviewRepository;
        this.bookReviewService = bookReviewService;
        this.bookReviewSummaryChatClient = bookReviewSummaryChatClient;
    }

    @Override
    @Cacheable(value = "aiReviewSummaries", key = "#bookId", unless = "#result == null")
    public BookReviewAiSummaryResponse generateSummary(Long bookId) throws BookException {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BookException("Book not found with id: " + bookId));

        BookRatingStatisticsDTO statistics = bookReviewService.getRatingStatistics(bookId);
        if (statistics.getTotalReviews() == null || statistics.getTotalReviews() == 0) {
            return new BookReviewAiSummaryResponse(
                    bookId,
                    book.getTitle(),
                    "No Reviews Yet",
                    0.0,
                    0L,
                    0L,
                    0,
                    "This book does not have enough reader reviews yet to generate an AI summary."
            );
        }

        Pageable samplePage = PageRequest.of(0, REVIEW_POOL_SIZE, Sort.by("createdAt").descending());
        List<BookReview> sampledPool = new ArrayList<>(
                bookReviewRepository.findByBookIdAndIsActiveTrue(bookId, samplePage).getContent()
        );
        Collections.shuffle(sampledPool);

        List<Map<String, Object>> sampledReviews = sampledPool.stream()
                .limit(REVIEW_SAMPLE_SIZE)
                .map(this::toAiReviewSample)
                .toList();

        String summary = bookReviewSummaryChatClient.prompt()
                .system("""
                        You summarize book reviews for a library UI.
                        Use only the provided statistics and sampled reviews.
                        Keep the answer under 120 words.
                        Mention the overall tone, 2-3 recurring positives or negatives, and whether the reception feels strong, mixed, or weak.
                        Do not invent facts beyond the provided review sample and rating statistics.
                        """)
                .user("""
                        Book: %s
                        Average rating: %s / 5
                        Total reviews: %s
                        Verified reader reviews: %s
                        Rating distribution: %s
                        Sampled reviews: %s
                        """.formatted(
                        book.getTitle(),
                        String.format("%.1f", statistics.getAverageRating()),
                        statistics.getTotalReviews(),
                        statistics.getVerifiedReaderReviews(),
                        statistics.getRatingDistribution(),
                        sampledReviews
                ))
                .call()
                .content();

        if (summary == null || summary.isBlank()) {
            summary = "Readers generally find this book useful, based on the available review sample and ratings.";
        }

        return new BookReviewAiSummaryResponse(
                bookId,
                book.getTitle(),
                resolveSentimentLabel(statistics.getAverageRating()),
                statistics.getAverageRating(),
                statistics.getTotalReviews(),
                statistics.getVerifiedReaderReviews(),
                sampledReviews.size(),
                summary
        );
    }

    private Map<String, Object> toAiReviewSample(BookReview review) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("rating", review.getRating());
        sample.put("title", review.getTitle());
        sample.put("reviewText", truncateReviewText(review.getReviewText()));
        sample.put("helpfulCount", review.getHelpfulCount());
        sample.put("verifiedReader", review.getIsVerifiedReader());
        sample.put("createdAt", review.getCreatedAt());
        return sample;
    }

    private String truncateReviewText(String reviewText) {
        if (reviewText == null) {
            return "";
        }
        String normalized = reviewText.trim().replaceAll("\\s+", " ");
        return normalized.length() <= REVIEW_TEXT_MAX_LENGTH
                ? normalized
                : normalized.substring(0, REVIEW_TEXT_MAX_LENGTH - 3) + "...";
    }

    private String resolveSentimentLabel(Double averageRating) {
        if (averageRating == null) {
            return "Insufficient Data";
        }
        if (averageRating >= 4.2) {
            return "Very Positive";
        }
        if (averageRating >= 3.4) {
            return "Generally Positive";
        }
        if (averageRating >= 2.6) {
            return "Mixed";
        }
        if (averageRating >= 1.8) {
            return "Mostly Negative";
        }
        return "Negative";
    }
}



