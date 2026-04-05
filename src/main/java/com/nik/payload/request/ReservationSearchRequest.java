package com.nik.payload.request;

import com.nik.domain.ReservationStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Search request DTO for filtering reservations
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservationSearchRequest {

    // User filter
    private Long userId;

    // Book filter
    private Long bookId;

    // Status filter
    private ReservationStatus status;

    // Active only (PENDING or AVAILABLE)
    private Boolean activeOnly;

    // Multi-status filter (mainly used for history views)
    private List<ReservationStatus> statuses;

    // Date range filter (inclusive, based on reservation event date)
    private LocalDate fromDate;
    private LocalDate toDate;

    // Pagination
    @Min(value = 0, message = "Page must be 0 or greater")
    private int page = 0;
    @Min(value = 1, message = "Size must be at least 1")
    @Max(value = 100, message = "Size cannot be greater than 100")
    private int size = 20;

    // Sorting
    private String sortBy = "reservedAt"; // reservedAt, availableAt, queuePosition, status
    private String sortDirection = "DESC"; // ASC or DESC
}
