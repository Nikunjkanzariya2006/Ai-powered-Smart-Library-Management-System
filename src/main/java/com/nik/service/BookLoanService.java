package com.nik.service;

import com.nik.domain.BookLoanStatus;
import com.nik.exception.BookException;
import com.nik.exception.BookLoanException;
import com.nik.exception.UserException;
import com.nik.payload.CheckoutStatistics;
import com.nik.payload.dto.BookLoanDTO;
import com.nik.payload.request.BookLoanSearchRequest;
import com.nik.payload.request.CheckinRequest;
import com.nik.payload.request.CheckoutRequest;
import com.nik.payload.request.RenewalRequest;
import com.nik.payload.response.PageResponse;

/**
 * Service interface for book loan operations (checkout/check-in). Handles book
 * lending and return business logic.
 */
public interface BookLoanService {
    /**
     * Checkout a book for the current authenticated user
     *
     * @param checkoutRequest Checkout details
     * @return BookLoan DTO
     * @throws BookLoanException if checkout fails (book unavailable, user has
     * overdue books, etc.)
     * @throws BookException if book not found
     * @throws UserException if user not found
     */
    BookLoanDTO checkoutBook(CheckoutRequest checkoutRequest)
            throws BookLoanException, BookException, UserException;

    /**
     * Checkout a book for a specific user (admin function)
     *
     * @param userId User ID
     * @param checkoutRequest Checkout details
     * @return BookLoan DTO
     * @throws BookLoanException if checkout fails
     * @throws BookException if book not found
     * @throws UserException if user not found
     */
    BookLoanDTO checkoutBookForUser(Long userId,
            CheckoutRequest checkoutRequest)
            throws BookLoanException, BookException, UserException;
    /**
     * Check in (return) a book
     *
     * @param checkinRequest Check-in details
     * @return Updated book loan DTO
     * @throws BookLoanException if book loan not found or already returned
     */
    BookLoanDTO checkinBook(CheckinRequest checkinRequest) throws BookLoanException;
    /**
     * Renew a book checkout (extend due date)
     *
     * @param renewalRequest Renewal details
     * @return Updated book loan DTO
     * @throws BookLoanException if renewal not allowed
     */
    BookLoanDTO renewCheckout(RenewalRequest renewalRequest) throws BookLoanException;
    /**
     * Get book loan by ID
     *
     * @param bookLoanId Book loan ID
     * @return BookLoan DTO
     * @throws BookLoanException if book loan not found
     */
    BookLoanDTO getBookLoanById(Long bookLoanId) throws BookLoanException;

    /**
     * Get book loans for current user with optional status filter
     *
     * @param status Optional status filter ("ACTIVE" for active loans, null or
     * other for all history)
     * @param page Page number
     * @param size Page size
     * @return Paginated book loans
     */
    PageResponse<BookLoanDTO> getMyBookLoans(
            BookLoanStatus status,
            int page,
            int size
);

    /**
     * Get book loans for a specific user with optional status filter
     *
     * @param userId User ID
     * @param status Optional status filter ("ACTIVE" for active loans, null or
     * other for all history)
     * @param page Page number
     * @param size Page size
     * @return Paginated book loans
     */
    PageResponse<BookLoanDTO> getUserBookLoans(
            Long userId,
            BookLoanStatus status,
            int page, int size
    );

    /**
     * get all book loans with filters
     *
     * @param searchRequest Search criteria
     * @return Paginated book loans
     */
    PageResponse<BookLoanDTO> getBookLoans(BookLoanSearchRequest searchRequest);
    /**
     * Update book loan (admin only)
     *
     * @param bookLoanId Book loan ID
     * @param updateRequest Update request with fields to update
     * @return Updated book loan DTO
     * @throws BookLoanException if book loan not found
     */
    BookLoanDTO updateBookLoan(Long bookLoanId, com.nik.payload.request.UpdateBookLoanRequest updateRequest) throws BookLoanException;

    /**
     * Update overdue status for all book loans (scheduled task)
     *
     * @return Number of book loans updated
     */
    int updateOverdueBookLoans();

    /**
     * Synchronize loan overdue flags/status and auto-generated overdue fines for a user.
     *
     * @param userId User ID
     * @return Number of loans updated
     */
    int synchronizeLoanStateForUser(Long userId);

    /**
     * Get checkout statistics
     *
     * @return Statistics object
     */
    CheckoutStatistics getCheckoutStatistics();
}

