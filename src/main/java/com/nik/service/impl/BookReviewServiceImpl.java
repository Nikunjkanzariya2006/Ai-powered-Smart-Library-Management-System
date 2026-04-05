package com.nik.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.nik.domain.BookLoanStatus;
import com.nik.exception.AuthenticationFailureException;
import com.nik.exception.BookException;
import com.nik.exception.BookReviewException;
import com.nik.exception.InvalidRequestException;
import com.nik.exception.UserException;
import com.nik.mapper.BookReviewMapper;
import com.nik.model.Book;
import com.nik.model.BookLoan;
import com.nik.model.BookReview;
import com.nik.model.User;
import com.nik.payload.dto.BookRatingStatisticsDTO;
import com.nik.payload.dto.BookReviewDTO;
import com.nik.payload.request.CreateReviewRequest;
import com.nik.payload.request.UpdateReviewRequest;
import com.nik.payload.response.PageResponse;
import com.nik.repository.BookLoanRepository;
import com.nik.repository.BookRepository;
import com.nik.repository.BookReviewRepository;
import com.nik.repository.UserRepository;
import com.nik.service.BookReviewService;

import jakarta.transaction.Transactional;

/**
 * Implementation of BookReviewService interface.
 * Handles all business logic for book reviews and ratings.
 */
@Service
@Transactional
public class BookReviewServiceImpl implements BookReviewService {

    private static final List<BookLoanStatus> REVIEW_ELIGIBLE_STATUSES = List.of(
            BookLoanStatus.CHECKED_OUT,
            BookLoanStatus.OVERDUE,
            BookLoanStatus.RETURNED
    );

    private final BookReviewRepository bookReviewRepository;
    private final BookRepository bookRepository;
    private final UserRepository userRepository;
    private final BookLoanRepository bookLoanRepository;
    private final BookReviewMapper bookReviewMapper;

    public BookReviewServiceImpl(
            BookReviewRepository bookReviewRepository,
            BookRepository bookRepository,
            UserRepository userRepository,
            BookLoanRepository bookLoanRepository,
            BookReviewMapper bookReviewMapper) {
        this.bookReviewRepository = bookReviewRepository;
        this.bookRepository = bookRepository;
        this.userRepository = userRepository;
        this.bookLoanRepository = bookLoanRepository;
        this.bookReviewMapper = bookReviewMapper;
    }

    @Override
    public BookReviewDTO createReview(CreateReviewRequest request)
            throws BookReviewException, BookException, UserException {

        User currentUser = getCurrentAuthenticatedUser();

        Book book = bookRepository.findById(request.getBookId())
                .orElseThrow(() -> new BookException("Book not found with id: " + request.getBookId()));

        if (bookReviewRepository.existsByUserIdAndBookIdAndIsActiveTrue(currentUser.getId(), request.getBookId())) {
            throw new BookReviewException(
                    "You have already reviewed this book. You can update your existing review instead.");
        }

        boolean hasBorrowedBook = hasUserBorrowedBook(currentUser.getId(), request.getBookId());
        if (!hasBorrowedBook) {
            throw new BookReviewException(
                    "You can only review books you have borrowed from the library. Please checkout this book first.");
        }

        BookReview bookReview = new BookReview();
        bookReview.setUser(currentUser);
        bookReview.setBook(book);
        bookReview.setRating(request.getRating());
        bookReview.setReviewText(request.getReviewText());
        bookReview.setTitle(request.getTitle());
        bookReview.setIsVerifiedReader(true); // Verified because they have read the book
        bookReview.setIsActive(true);
        bookReview.setHelpfulCount(0);

        BookReview savedReview = bookReviewRepository.save(bookReview);

        return bookReviewMapper.toDTO(savedReview);
    }

    @Override
    public BookReviewDTO updateReview(Long reviewId, UpdateReviewRequest request) throws BookReviewException {

        User currentUser = getCurrentAuthenticatedUser();

        BookReview bookReview = bookReviewRepository.findById(reviewId)
                .orElseThrow(() -> new BookReviewException("Review not found with id: " + reviewId));

        if (!bookReview.getUser().getId().equals(currentUser.getId())) {
            throw new BookReviewException("You can only update your own reviews");
        }

        bookReview.setRating(request.getRating());
        bookReview.setReviewText(request.getReviewText());
        bookReview.setTitle(request.getTitle());

        BookReview updatedReview = bookReviewRepository.save(bookReview);

        return bookReviewMapper.toDTO(updatedReview);
    }

    @Override
    public void deleteReview(Long reviewId) throws BookReviewException {

        User currentUser = getCurrentAuthenticatedUser();

        BookReview bookReview = bookReviewRepository.findById(reviewId)
                .orElseThrow(() -> new BookReviewException("Review not found with id: " + reviewId));

        if (!bookReview.getUser().getId().equals(currentUser.getId())) {
            throw new BookReviewException("You can only delete your own reviews");
        }

        bookReview.setIsActive(false);
        bookReviewRepository.save(bookReview);
    }

    @Override
    public BookReviewDTO getReviewById(Long reviewId) throws BookReviewException {
        BookReview bookReview = bookReviewRepository.findById(reviewId)
                .orElseThrow(() -> new BookReviewException("Review not found with id: " + reviewId));
        return bookReviewMapper.toDTO(bookReview);
    }

    @Override
    public PageResponse<BookReviewDTO> getReviewsByBookWithFilter(
            Long bookId,
            com.nik.domain.ReviewFilterType filterType,
            Integer rating,
            int page,
            int size) {

        Page<BookReview> reviewPage;
        Pageable pageable;

        switch (filterType) {
            case BY_RATING:
                if (rating == null) {
                    throw new InvalidRequestException("Rating is required when filter type is BY_RATING");
                }
                if (rating < 1 || rating > 5) {
                    throw new InvalidRequestException("Rating must be between 1 and 5");
                }
                pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
                reviewPage = bookReviewRepository.findByBookIdAndRatingAndIsActiveTrue(bookId, rating, pageable);
                break;

            case VERIFIED_ONLY:
                pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
                reviewPage = bookReviewRepository.findByBookIdAndIsVerifiedReaderTrueAndIsActiveTrue(bookId, pageable);
                break;

            case TOP_HELPFUL:
                pageable = PageRequest.of(page, size);
                reviewPage = bookReviewRepository.findTopHelpfulReviewsByBookId(bookId, pageable);
                break;

            case ALL:
            default:
                pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
                reviewPage = bookReviewRepository.findByBookIdAndIsActiveTrue(bookId, pageable);
                break;
        }

        return convertToPageResponse(reviewPage);
    }

    @Override
    public PageResponse<BookReviewDTO> getMyReviews(int page, int size) {
        User currentUser = getCurrentAuthenticatedUser();
        return getReviewsByUser(currentUser.getId(), page, size);
    }

    @Override
    public PageResponse<BookReviewDTO> getReviewsByUser(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<BookReview> reviewPage = bookReviewRepository.findByUserIdAndIsActiveTrue(userId, pageable);
        return convertToPageResponse(reviewPage);
    }

    @Override
    public BookRatingStatisticsDTO getRatingStatistics(Long bookId) throws BookException {

        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BookException("Book not found with id: " + bookId));

        Double averageRating = bookReviewRepository.getAverageRatingByBookId(bookId);

        Long totalReviews = bookReviewRepository.countReviewsByBookId(bookId);

        List<Object[]> ratingData = bookReviewRepository.countReviewsByRatingForBook(bookId);
        Map<Integer, Long> ratingDistribution = new HashMap<>();

        // Initialize all ratings with 0
        for (int i = 1; i <= 5; i++) {
            ratingDistribution.put(i, 0L);
        }

        // Fill in actual counts
        for (Object[] row : ratingData) {
            Integer rating = (Integer) row[0];
            Long count = (Long) row[1];
            ratingDistribution.put(rating, count);
        }

        Pageable pageable = PageRequest.of(0, 1);
        Long verifiedReaderReviews = bookReviewRepository
                .findByBookIdAndIsVerifiedReaderTrueAndIsActiveTrue(bookId, pageable)
                .getTotalElements();

        return BookRatingStatisticsDTO.builder()
                .bookId(bookId)
                .bookTitle(book.getTitle())
                .averageRating(averageRating != null ? averageRating : 0.0)
                .totalReviews(totalReviews)
                .ratingDistribution(ratingDistribution)
                .verifiedReaderReviews(verifiedReaderReviews)
                .build();
    }

    @Override
    public BookReviewDTO markReviewAsHelpful(Long reviewId) throws BookReviewException {

        BookReview bookReview = bookReviewRepository.findById(reviewId)
                .orElseThrow(() -> new BookReviewException("Review not found with id: " + reviewId));

        bookReview.setHelpfulCount(bookReview.getHelpfulCount() + 1);

        BookReview updatedReview = bookReviewRepository.save(bookReview);

        return bookReviewMapper.toDTO(updatedReview);
    }

    @Override
    public boolean canUserReviewBook(Long bookId) {
        User currentUser = getCurrentAuthenticatedUser();
        return canUserReviewBook(currentUser.getId(), bookId);
    }

    @Override
    public boolean canUserReviewBook(Long userId, Long bookId) {
        boolean alreadyReviewed = bookReviewRepository.existsByUserIdAndBookIdAndIsActiveTrue(userId, bookId);
        boolean hasBorrowedBook = hasUserBorrowedBook(userId, bookId);

        return !alreadyReviewed && hasBorrowedBook;
    }

    @Override
    public long getTotalReviewCount() {
        return bookReviewRepository.countByIsActiveTrue();
    }

    /**
     * Check if user has ever borrowed this book in a review-eligible state.
     */
    private boolean hasUserBorrowedBook(Long userId, Long bookId) {
        return bookLoanRepository.existsByUserIdAndBookIdAndStatusIn(userId, bookId, REVIEW_ELIGIBLE_STATUSES);
    }

    private User getCurrentAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationFailureException("User is not authenticated");
        }
        String email = authentication.getName();
        User user = userRepository.findByEmail(email);
        if (user == null) {
            throw new AuthenticationFailureException("Authenticated user could not be resolved");
        }
        return user;
    }

    private PageResponse<BookReviewDTO> convertToPageResponse(Page<BookReview> reviewPage) {
        List<BookReviewDTO> reviewDTOs = reviewPage.getContent()
                .stream()
                .map(bookReviewMapper::toDTO)
                .collect(Collectors.toList());

        return new PageResponse<>(
                reviewDTOs,
                reviewPage.getNumber(),
                reviewPage.getSize(),
                reviewPage.getTotalElements(),
                reviewPage.getTotalPages(),
                reviewPage.isLast(),
                reviewPage.isFirst(),
                reviewPage.isEmpty()
        );
    }

}


