package com.nik.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity representing a book in the library catalog.
 * Supports full CRUD operations with validation and automatic timestamp management.
 */
@Entity
@Table(
        name = "books",
        indexes = {
                @Index(name = "idx_books_isbn", columnList = "isbn", unique = true),
                @Index(name = "idx_books_title", columnList = "title"),
                @Index(name = "idx_books_author", columnList = "author"),
                @Index(name = "idx_books_genre_id", columnList = "genre_id")
        }
)

@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "ISBN is mandatory")
    @Pattern(regexp = "^(?:ISBN(?:-1[03])?:? )?(?=[0-9X]{10}$|(?=(?:[0-9]+[- ]){3})[- 0-9X]{13}$|97[89][0-9]{10}$|(?=(?:[0-9]+[- ]){4})[- 0-9]{17}$)(?:97[89][- ]?)?[0-9]{1,5}[- ]?[0-9]+[- ]?[0-9]+[- ]?[0-9X]$",
            message = "ISBN format is invalid")
    @Column(nullable = false, unique = true, length = 20, updatable = false)
    private String isbn;

    @NotBlank(message = "Title is mandatory")
    @Size(min = 1, max = 255, message = "Title must be between 1 and 255 characters")
    @Column(nullable = false)
    private String title;

    @NotBlank(message = "Author is mandatory")
    @Size(min = 1, max = 255, message = "Author name must be between 1 and 255 characters")
    @Column(nullable = false)
    private String author;

    @NotNull(message = "Genre is mandatory")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "genre_id", nullable = false)
    private Genre genre;

    @Size(max = 100, message = "Publisher name must not exceed 100 characters")
    private String publisher;

    @Column(name = "publication_date")
    private LocalDate publicationDate;

    @Column(length = 20)
    private String language;

    @Min(value = 1, message = "Pages must be at least 1")
    @Max(value = 50000, message = "Pages must not exceed 50000")
    private Integer pages;

    @Column(length = 2000)
    private String description;

    @Min(value = 0, message = "Total copies cannot be negative")
    @NotNull(message = "Total copies is mandatory")
    @Column(name = "total_copies", nullable = false)
    private Integer totalCopies;

    @Min(value = 0, message = "Available copies cannot be negative")
    @NotNull(message = "Available copies is mandatory")
    @Column(name = "available_copies", nullable = false)
    private Integer availableCopies;

    @DecimalMin(value = "0.0", inclusive = true, message = "Price cannot be negative")
    @Digits(integer = 8, fraction = 2, message = "Price must have at most 8 integer digits and 2 decimal places")
    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    @Size(max = 500, message = "Image URL must not exceed 500 characters")
    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;

    @Size(max = 20, message = "Dewey Decimal must not exceed 20 characters")
    @Column(name = "dewey_decimal", length = 20)
    private String deweyDecimal;

    @Size(max = 30, message = "Library of Congress code must not exceed 30 characters")
    @Column(name = "library_of_congress_code", length = 30)
    private String libraryOfCongressCode;

    @Size(max = 50, message = "Call number must not exceed 50 characters")
    @Column(name = "call_number", length = 50)
    private String callNumber;

    @Size(max = 30, message = "Audience level must not exceed 30 characters")
    @Column(name = "audience_level", length = 30)
    private String audienceLevel;

    @Size(max = 500, message = "Subject headings must not exceed 500 characters")
    @Column(name = "subject_headings", length = 500)
    private String subjectHeadings;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(nullable = false)
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * Custom validation to ensure available copies never exceed total copies
     */
    @AssertTrue(message = "Available copies cannot exceed total copies")
    public boolean isAvailableCopiesValid() {
        if (totalCopies == null || availableCopies == null) {
            return true; // Let @NotNull handle null validation
        }
        return availableCopies <= totalCopies;
    }
}

