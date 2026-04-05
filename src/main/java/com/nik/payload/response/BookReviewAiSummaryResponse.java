package com.nik.payload.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookReviewAiSummaryResponse {

    private Long bookId;
    private String bookTitle;
    private String sentimentLabel;
    private Double averageRating;
    private Long totalReviews;
    private Long verifiedReaderReviews;
    private Integer sampledReviews;
    private String summary;
}
