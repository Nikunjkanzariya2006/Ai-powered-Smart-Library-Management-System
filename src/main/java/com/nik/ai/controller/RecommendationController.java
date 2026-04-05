package com.nik.ai.controller;

import com.nik.ai.service.RecommendationService;
import com.nik.exception.UserException;
import com.nik.payload.response.PersonalizedRecommendationsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @GetMapping("/me")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<PersonalizedRecommendationsResponse> getMyRecommendations(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long nonce
    ) throws UserException {
        return ResponseEntity.ok(recommendationService.getPersonalizedRecommendations(limit, nonce));
    }
}
