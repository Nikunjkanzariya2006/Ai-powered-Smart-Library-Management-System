package com.nik.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendedBookDTO {

    private BookDTO book;
    private Double score;
    private String reason;
}
