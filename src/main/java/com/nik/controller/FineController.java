package com.nik.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nik.domain.FineStatus;
import com.nik.domain.FineType;
import com.nik.payload.dto.FineDTO;
import com.nik.payload.request.CreateFineRequest;
import com.nik.payload.response.PageResponse;
import com.nik.payload.response.PaymentInitiateResponse;
import com.nik.service.FineService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST Controller for Fine operations.
 * Handles fine creation, payment, waiving, and queries.
 */
@RestController
@RequestMapping("/api/fines")
@RequiredArgsConstructor
@Slf4j
public class FineController {

    private final FineService fineService;

    /**
     * Create a new fine manually (Admin only)
     * POST /api/fines
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<FineDTO> createFine(@Valid @RequestBody CreateFineRequest createRequest) {
        log.info("Admin creating fine for book loan: {}", createRequest.getBookLoanId());
        FineDTO fine = fineService.createFine(createRequest);
        return new ResponseEntity<>(fine, HttpStatus.CREATED);
    }

    /**
     * Pay a fine fully
     * POST /api/fines/{id}/pay
     */
    @PostMapping("/{id}/pay")
    public ResponseEntity<PaymentInitiateResponse> payFineFully(
        @PathVariable Long id, 
        @RequestParam(required = false) String transactionId) {
        log.info("Full payment request for fine: {}", id);
        PaymentInitiateResponse response = fineService.payFineFully(id, transactionId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get fine by ID
     * GET /api/fines/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<FineDTO> getFineById(@PathVariable Long id) {
        FineDTO fine = fineService.getFineById(id);
        return ResponseEntity.ok(fine);
    }

    /**
     * Get fines for a book loan
     * GET /api/fines/book-loan/{bookLoanId}
     */
    @GetMapping("/book-loan/{bookLoanId}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<List<FineDTO>> getFinesByBookLoanId(@PathVariable Long bookLoanId) {
        List<FineDTO> fines = fineService.getFinesByBookLoanId(bookLoanId);
        return ResponseEntity.ok(fines);
    }

    /**
     * Get my fines (current user) with optional filters
     * GET /api/fines/my?status=PENDING&type=OVERDUE
     */
    @GetMapping("/my")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<List<FineDTO>> getMyFines(
            @RequestParam(required = false) FineStatus status,
            @RequestParam(required = false) FineType type) {
        List<FineDTO> fines = fineService.getMyFines(status, type);
        return ResponseEntity.ok(fines);
    }

    /**
     * Get all fines with filtering (Admin only)
     * GET /api/fines?status=PENDING&type=OVERDUE&userId=123&page=0&size=20
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageResponse<FineDTO>> getAllFines(
            @RequestParam(required = false) FineStatus status,
            @RequestParam(required = false) FineType type,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<FineDTO> fines = fineService.getAllFines(status, type, userId, page, size);
        return ResponseEntity.ok(fines);
    }

    /**
     * Get my total unpaid fines (current user)
     * GET /api/fines/my/total-unpaid
     */
    @GetMapping("/my/total-unpaid")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<TotalFinesResponse> getMyTotalUnpaidFines() {
        Long total = fineService.getMyTotalUnpaidFines();
        return ResponseEntity.ok(new TotalFinesResponse(total));
    }

    /**
     * Get total unpaid fines for a user (Admin only)
     * GET /api/fines/statistics/user/{userId}/unpaid
     */
    @GetMapping("/statistics/user/{userId}/unpaid")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TotalFinesResponse> getTotalUnpaidFinesByUserId(@PathVariable Long userId) {
        Long total = fineService.getTotalUnpaidFinesByUserId(userId);
        return ResponseEntity.ok(new TotalFinesResponse(total));
    }

    /**
     * Get total collected fines (Admin only)
     * GET /api/fines/statistics/collected
     */
    @GetMapping("/statistics/collected")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TotalFinesResponse> getTotalCollectedFines() {
        Long total = fineService.getTotalCollectedFines();
        return ResponseEntity.ok(new TotalFinesResponse(total));
    }

    /**
     * Get total outstanding fines (Admin only)
     * GET /api/fines/statistics/outstanding
     */
    @GetMapping("/statistics/outstanding")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TotalFinesResponse> getTotalOutstandingFines() {
        Long total = fineService.getTotalOutstandingFines();
        return ResponseEntity.ok(new TotalFinesResponse(total));
    }

    /**
     * Check if user has unpaid fines (Admin only)
     * GET /api/fines/statistics/user/{userId}/has-unpaid
     */
    @GetMapping("/statistics/user/{userId}/has-unpaid")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<HasUnpaidFinesResponse> hasUnpaidFines(@PathVariable Long userId) {
        boolean hasUnpaid = fineService.hasUnpaidFines(userId);
        return ResponseEntity.ok(new HasUnpaidFinesResponse(hasUnpaid));
    }

    public static class TotalFinesResponse {
        public Long total;

        public TotalFinesResponse(Long total) {
            this.total = total;
        }
    }

    public static class HasUnpaidFinesResponse {
        public boolean hasUnpaidFines;

        public HasUnpaidFinesResponse(boolean hasUnpaidFines) {
            this.hasUnpaidFines = hasUnpaidFines;
        }
    }
}

