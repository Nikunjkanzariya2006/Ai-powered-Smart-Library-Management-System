package com.nik.payload.response;

import com.nik.payload.dto.RecommendedBookDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PersonalizedRecommendationsResponse {

    private String strategy;
    private int interestCount;
    private int borrowHistoryCount;
    private int wishlistCount;
    private int reviewCount;
    private List<RecommendedBookDTO> items;
}
