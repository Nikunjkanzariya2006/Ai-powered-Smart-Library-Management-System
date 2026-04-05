package com.nik.repository;

import com.nik.model.BookReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface BookReviewRepository extends JpaRepository<BookReview, Long> {

    /**
     * Find all active reviews for a specific book
     */
    Page<BookReview> findByBookIdAndIsActiveTrue(Long bookId, Pageable pageable);

    /**
     * Count verified reader reviews for a specific book.
     */
    long countByBookIdAndIsVerifiedReaderTrueAndIsActiveTrue(Long bookId);

    /**
     * Find all reviews by a specific user
     */
    Page<BookReview> findByUserIdAndIsActiveTrue(Long userId, Pageable pageable);

    List<BookReview> findAllByUserIdAndIsActiveTrue(Long userId);

    /**
     * Find a specific review by user and book
     */
    Optional<BookReview> findByUserIdAndBookId(Long userId, Long bookId);

    /**
     * Check if a user has already reviewed a book
     */
    boolean existsByUserIdAndBookId(Long userId, Long bookId);

    boolean existsByUserIdAndBookIdAndIsActiveTrue(Long userId, Long bookId);

    /**
     * Get average rating for a book
     */
    @Query("SELECT AVG(br.rating) FROM BookReview br WHERE br.book.id = :bookId AND br.isActive = true")
    Double getAverageRatingByBookId(@Param("bookId") Long bookId);

    /**
     * Count total reviews for a book
     */
    @Query("SELECT COUNT(br) FROM BookReview br WHERE br.book.id = :bookId AND br.isActive = true")
    Long countReviewsByBookId(@Param("bookId") Long bookId);

    /**
     * Get reviews by rating for a specific book
     */
    Page<BookReview> findByBookIdAndRatingAndIsActiveTrue(Long bookId, Integer rating, Pageable pageable);

    /**
     * Get verified reader reviews for a book
     */
    Page<BookReview> findByBookIdAndIsVerifiedReaderTrueAndIsActiveTrue(Long bookId, Pageable pageable);

    /**
     * Get top helpful reviews for a book
     */
    @Query("SELECT br FROM BookReview br WHERE br.book.id = :bookId AND br.isActive = true ORDER BY br.helpfulCount DESC")
    Page<BookReview> findTopHelpfulReviewsByBookId(@Param("bookId") Long bookId, Pageable pageable);

    /**
     * Count reviews by rating for a book (for rating distribution)
     */
    @Query("SELECT br.rating, COUNT(br) FROM BookReview br WHERE br.book.id = :bookId AND br.isActive = true GROUP BY br.rating")
    java.util.List<Object[]> countReviewsByRatingForBook(@Param("bookId") Long bookId);

    /**
     * Count all active reviews in the system
     */
    long countByIsActiveTrue();

    /**
     * Count all active reviews for a user.
     */
    long countByUserIdAndIsActiveTrue(Long userId);
}

