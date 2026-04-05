package com.nik.ai.service;

import com.nik.exception.UserException;
import com.nik.payload.response.PersonalizedRecommendationsResponse;

public interface RecommendationService {

    PersonalizedRecommendationsResponse getPersonalizedRecommendations(Integer limit, Long nonce) throws UserException;
}
