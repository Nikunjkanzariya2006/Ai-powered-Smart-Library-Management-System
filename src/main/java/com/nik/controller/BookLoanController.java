package com.nik.controller;

import com.nik.domain.BookLoanStatus;
import com.nik.exception.BookException;
import com.nik.exception.BookLoanException;
import com.nik.exception.UserException;
import com.nik.payload.*;
import com.nik.payload.dto.BookLoanDTO;
import com.nik.payload.request.CheckinRequest;
import com.nik.payload.request.CheckoutRequest;
import com.nik.payload.request.RenewalRequest;
import com.nik.payload.request.BookLoanSearchRequest;
import com.nik.payload.response.ApiResponse;
import com.nik.payload.response.PageResponse;
import com.nik.service.BookLoanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * REST Controller for BookLoan/Checkout operations.
 * Handles book checkout, check-in, renewals, and book loan history.
 */
@RestController
@RequestMapping("/api/book-loans")
@RequiredArgsConstructor
@Slf4j
public class BookLoanController {

    private final BookLoanService bookLoanService;

    /**
     * Checkout a book (for current authenticated user)
     * POST /api/book-loans/checkout
     */
    @PostMapping("/checkout")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> checkoutBook(@Valid @RequestBody CheckoutRequest checkoutRequest) {
        try {
            log.info("Checkout request received for book ID: {}", checkoutRequest.getBookId());
            BookLoanDTO bookLoan = bookLoanService.checkoutBook(checkoutRequest);
            return new ResponseEntity<>(bookLoan, HttpStatus.CREATED);
        } catch (BookLoanException | BookException | UserException e) {
            log.error("Checkout failed", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse(e.getMessage(), false));
        }
    }

    /**
     * Checkout a book for a specific user (admin operation)
     * POST /api/book-loans/checkout/user/{userId}
     */
    @PostMapping("/checkout/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> checkoutBookForUser(
            @PathVariable Long userId,
            @Valid @RequestBody CheckoutRequest checkoutRequest) {
        try {
            log.info("Admin checkout request for user ID: {}, book ID: {}", userId, checkoutRequest.getBookId());
            BookLoanDTO bookLoan = bookLoanService.checkoutBookForUser(userId, checkoutRequest);
            return new ResponseEntity<>(bookLoan, HttpStatus.CREATED);
        } catch (BookLoanException | BookException | UserException e) {
            log.error("Checkout failed for user: {}", userId, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse(e.getMessage(), false));
        }
    }

    /**
     * Check in (return) a book
     * POST /api/book-loans/checkin
     */
    @PostMapping("/checkin")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> checkinBook(@Valid @RequestBody CheckinRequest checkinRequest) {
        try {
            log.info("Checkin request received for loan ID: {}", checkinRequest.getBookLoanId());
            BookLoanDTO bookLoan = bookLoanService.checkinBook(checkinRequest);
            return ResponseEntity.ok(bookLoan);
        } catch (BookLoanException e) {
            log.error("Checkin failed for loan ID: {}", checkinRequest.getBookLoanId(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse(e.getMessage(), false));
        }
    }

    /**
     * Renew a book checkout (extend due date)
     * POST /api/book-loans/renew
     */
    @PostMapping("/renew")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> renewCheckout(@Valid @RequestBody RenewalRequest renewalRequest) {
        try {
            log.info("Renewal request received for loan ID: {}", renewalRequest.getBookLoanId());
            BookLoanDTO bookLoan = bookLoanService.renewCheckout(renewalRequest);
            return ResponseEntity.ok(bookLoan);
        } catch (BookLoanException e) {
            log.error("Renewal failed for loan ID: {}", renewalRequest.getBookLoanId(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse(e.getMessage(), false));
        }
    }

    /**
     * Get book loan by ID
     * GET /api/book-loans/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> getBookLoanById(@PathVariable Long id) {
        try {
            BookLoanDTO bookLoan = bookLoanService.getBookLoanById(id);
            return ResponseEntity.ok(bookLoan);
        } catch (BookLoanException e) {
            log.error("Book loan not found: {}", id, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiResponse(e.getMessage(), false));
        }
    }

    /**
     * Get my book loans with optional status filter
     * GET /api/book-loans/my?status=ACTIVE&page=0&size=20
     *
     * @param status Optional filter - "ACTIVE" for active loans only, null/other for all history
     * @param page Page number
     * @param size Page size
     */
    @GetMapping("/my")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<PageResponse<BookLoanDTO>> getMyBookLoans(
            @RequestParam(required = false) BookLoanStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<BookLoanDTO> bookLoans = bookLoanService.getMyBookLoans(status, page, size);
        return ResponseEntity.ok(bookLoans);
    }



    /**
     * Get book loans for a specific user with optional status filter (Admin)
     * GET /api/book-loans/user/{userId}?status=ACTIVE&page=0&size=20
     *
     * @param userId User ID
     * @param status Optional filter - "ACTIVE" for active loans only, null/other for all history
     * @param page Page number
     * @param size Page size
     */
    @GetMapping("/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageResponse<BookLoanDTO>> getUserBookLoans(
            @PathVariable Long userId,
            @RequestParam(required = false) BookLoanStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<BookLoanDTO> bookLoans = bookLoanService.getUserBookLoans(userId, status, page, size);
        return ResponseEntity.ok(bookLoans);
    }


    /**
     * Search book loans with filters
     * POST /api/book-loans/search
     */
    @PostMapping("/search")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageResponse<BookLoanDTO>> getAllBookLoans(
            @Valid @RequestBody(required = false) BookLoanSearchRequest searchRequest) {
        PageResponse<BookLoanDTO> bookLoans = bookLoanService.getBookLoans(searchRequest);
        return ResponseEntity.ok(bookLoans);
    }

    /**
     * Update a book loan (admin only)
     * PUT /api/book-loans/{id}
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateBookLoan(
            @PathVariable Long id,
            @Valid @RequestBody com.nik.payload.request.UpdateBookLoanRequest updateRequest) {
        try {
            log.info("Admin update request for book loan ID: {}", id);
            BookLoanDTO bookLoan = bookLoanService.updateBookLoan(id, updateRequest);
            return ResponseEntity.ok(bookLoan);
        } catch (BookLoanException e) {
            log.error("Failed to update book loan: {}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse(e.getMessage(), false));
        }
    }

    /**
     * Update overdue book loans (scheduled/admin task)
     * POST /api/book-loans/admin/update-overdue
     */
    @PostMapping("/admin/update-overdue")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UpdateOverdueResponse> updateOverdueBookLoans() {
        log.info("Admin triggered overdue update");
        int updateCount = bookLoanService.updateOverdueBookLoans();
        return ResponseEntity.ok(new UpdateOverdueResponse(updateCount));
    }

    /**
     * Get checkout statistics
     * GET /api/book-loans/statistics
     */
    @GetMapping("/statistics")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CheckoutStatistics> getCheckoutStatistics() {
        CheckoutStatistics statistics = bookLoanService.getCheckoutStatistics();
        return ResponseEntity.ok(statistics);
    }

    /**
     * Response DTO for unpaid fines
     */
    public static class UnpaidFinesResponse {
        public BigDecimal totalUnpaidFines;

        public UnpaidFinesResponse(BigDecimal totalUnpaidFines) {

            this.totalUnpaidFines = totalUnpaidFines;
        }
    }

    /**
     * Response DTO for update overdue operation
     */
    public static class UpdateOverdueResponse {
        public int bookLoansUpdated;
        public String message;

        public UpdateOverdueResponse(int bookLoansUpdated) {
            this.bookLoansUpdated = bookLoansUpdated;
            this.message = bookLoansUpdated + " book loan(s) marked as overdue";
        }
    }




}

