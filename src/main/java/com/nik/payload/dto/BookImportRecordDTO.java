package com.nik.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookImportRecordDTO {

    private String isbn;
    private String title;
    private String author;
    private Long genreId;
    private String genreName;
    private String genreCode;
    private String publisher;
    private LocalDate publicationDate;
    private String language;
    private Integer pages;
    private String description;
    private Integer totalCopies;
    private Integer availableCopies;
    private BigDecimal price;
    private String coverImageUrl;
    private String deweyDecimal;
    private String libraryOfCongressCode;
    private String callNumber;
    private String audienceLevel;
    private String subjectHeadings;
    private Boolean active;
}
