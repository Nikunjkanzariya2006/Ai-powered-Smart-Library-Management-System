package com.nik.payload.request;

import com.nik.domain.BookLoanStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Request DTO for searching book loans
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookLoanSearchRequest {

    private Long userId;
    private Long bookId;
    private BookLoanStatus status;
    private Boolean overdueOnly;
    private Boolean unpaidFinesOnly;
    private LocalDate startDate;
    private LocalDate endDate;

    @Min(value = 0, message = "Page must be 0 or greater")
    private Integer page = 0;

    @Min(value = 1, message = "Size must be at least 1")
    @Max(value = 100, message = "Size cannot be greater than 100")
    private Integer size = 20;
    private String sortBy = "createdAt";
    private String sortDirection = "DESC";
}
