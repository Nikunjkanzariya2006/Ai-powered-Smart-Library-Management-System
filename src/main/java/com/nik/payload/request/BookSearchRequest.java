package com.nik.payload.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for book search and filtering operations
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookSearchRequest {

    private String searchTerm;
    private Long genreId;
    private Boolean availableOnly;
    private Boolean activeOnly = true;

    @Min(value = 0, message = "Page must be 0 or greater")
    private Integer page = 0;

    @Min(value = 1, message = "Size must be at least 1")
    @Max(value = 100, message = "Size cannot be greater than 100")
    private Integer size = 20;
    private String sortBy = "createdAt";
    private String sortDirection = "DESC";
}
