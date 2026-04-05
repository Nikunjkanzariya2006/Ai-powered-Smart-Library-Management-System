package com.nik.payload.request;

import com.nik.domain.BookLoanStatus;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request DTO for updating a book loan (admin only)
 * Allows admin to modify key book loan attributes
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateBookLoanRequest {

    private BookLoanStatus status;

    private LocalDate dueDate;

    private LocalDate returnDate;

    @Min(value = 0, message = "Max renewals cannot be negative")
    private Integer maxRenewals;

    private BigDecimal fineAmount;

    private Boolean finePaid;

    private String notes;
}
