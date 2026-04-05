package com.nik.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

import com.nik.domain.ReservationStatus;
import com.nik.exception.BookException;
import com.nik.exception.ReservationException;
import com.nik.exception.UserException;
import com.nik.payload.dto.ReservationDTO;
import com.nik.payload.request.ReservationRequest;
import com.nik.payload.request.ReservationSearchRequest;
import com.nik.payload.response.ApiResponse;
import com.nik.payload.response.PageResponse;
import com.nik.service.ReservationService;

import jakarta.validation.Valid;

/**
 * REST Controller for Reservation/Hold operations.
 * Handles book reservations, cancellations, and reservation history.
 */
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /**
     * Create a reservation for current authenticated user
     * POST /api/reservations
     */
    @PostMapping
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> createReservation(@Valid @RequestBody ReservationRequest reservationRequest) {
        try {
            ReservationDTO reservation = reservationService.createReservation(reservationRequest);
            return new ResponseEntity<>(reservation, HttpStatus.CREATED);
        } catch (ReservationException | BookException | UserException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ApiResponse(e.getMessage(), false)
            );
        }
    }

    /**
     * Cancel a reservation
     * DELETE /api/reservations/{id}
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> cancelReservation(@PathVariable Long id) {
        try {
            ReservationDTO reservation = reservationService.cancelReservation(id);
            return ResponseEntity.ok(reservation);
        } catch (ReservationException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ApiResponse(e.getMessage(), false)
            );
        }
    }

    /**
     * Fulfill a reservation (mark as checked out)
     * POST /api/reservations/{id}/fulfill
     */
    @PostMapping("/{id}/fulfill")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> fulfillReservation(@PathVariable Long id) {
        try {
            ReservationDTO reservation = reservationService
            .fulfillReservation(id);
            return ResponseEntity.ok(reservation);
        } catch (ReservationException | BookException | UserException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ApiResponse(e.getMessage(), false)
            );
        }
    }

    /**
     * Get reservation by ID
     * GET /api/reservations/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> getReservationById(@PathVariable Long id) {
        try {
            ReservationDTO reservation = reservationService.getReservationById(id);
            return ResponseEntity.ok(reservation);
        } catch (ReservationException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ApiResponse(e.getMessage(), false)
            );
        }
    }

    /**
     * Search my reservations (current user) with filters
     * GET /api/reservations/my
     *
     * Query params:
     * - status: Filter by status (PENDING, AVAILABLE, FULFILLED, CANCELLED, EXPIRED)
     * - activeOnly: Show only active reservations (PENDING or AVAILABLE)
     * - page: Page number (default: 0)
     * - size: Page size (default: 20)
     * - sortBy: Sort field (default: reservedAt)
     * - sortDirection: ASC or DESC (default: DESC)
     */
    @GetMapping("/my")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<PageResponse<ReservationDTO>> getMyReservations(
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(required = false) List<ReservationStatus> statuses,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "reservedAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {

        ReservationSearchRequest searchRequest = new ReservationSearchRequest();
        searchRequest.setStatus(status);
        searchRequest.setStatuses(statuses);
        searchRequest.setFromDate(fromDate);
        searchRequest.setToDate(toDate);
        searchRequest.setActiveOnly(activeOnly);
        searchRequest.setPage(page);
        searchRequest.setSize(size);
        searchRequest.setSortBy(sortBy);
        searchRequest.setSortDirection(sortDirection);

        PageResponse<ReservationDTO> reservations = reservationService.getMyReservations(searchRequest);
        return ResponseEntity.ok(reservations);
    }

    /**
     * Search all reservations with filters (admin operation)
     * GET /api/reservations
     *
     * Query params:
     * - userId: Filter by user ID
     * - bookId: Filter by book ID
     * - status: Filter by status
     * - activeOnly: Show only active reservations
     * - page, size, sortBy, sortDirection
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageResponse<ReservationDTO>> searchReservations(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long bookId,
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(required = false) List<ReservationStatus> statuses,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "reservedAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {

        ReservationSearchRequest searchRequest = new ReservationSearchRequest();
        searchRequest.setUserId(userId);
        searchRequest.setBookId(bookId);
        searchRequest.setStatus(status);
        searchRequest.setStatuses(statuses);
        searchRequest.setFromDate(fromDate);
        searchRequest.setToDate(toDate);
        searchRequest.setActiveOnly(activeOnly);
        searchRequest.setPage(page);
        searchRequest.setSize(size);
        searchRequest.setSortBy(sortBy);
        searchRequest.setSortDirection(sortDirection);

        PageResponse<ReservationDTO> reservations = reservationService.searchReservations(searchRequest);
        return ResponseEntity.ok(reservations);
    }

    /**
     * Get queue position for a reservation
     * GET /api/reservations/{id}/queue-position
     */
    @GetMapping("/{id}/queue-position")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> getQueuePosition(@PathVariable Long id) {
        try {
            int position = reservationService.getQueuePosition(id);
            return ResponseEntity.ok(new QueuePositionResponse(position));
        } catch (ReservationException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ApiResponse(e.getMessage(), false)
            );
        }
    }

    /**
     * Response DTO for queue position
     */
    public static class QueuePositionResponse {
        public int queuePosition;
        public String message;

        public QueuePositionResponse(int queuePosition) {
            this.queuePosition = queuePosition;
            if (queuePosition == 0) {
                this.message = "Reservation is not in queue";
            } else if (queuePosition == 1) {
                this.message = "You are next in line!";
            } else {
                this.message = "There are " + (queuePosition - 1) + " person(s) ahead of you";
            }
        }
    }
}

