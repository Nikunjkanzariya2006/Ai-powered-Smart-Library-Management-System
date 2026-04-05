package com.nik.service;

import com.nik.exception.PaymentException;
import com.nik.exception.SubscriptionException;
import com.nik.exception.UserException;
import com.nik.payload.dto.SubscriptionCancellationEligibilityDTO;
import com.nik.payload.dto.SubscriptionDTO;
import com.nik.payload.request.SubscribeRequest;
import com.nik.payload.response.PaymentInitiateResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Service interface for subscription operations
 */
public interface SubscriptionService {

    /**
     * Create new subscription with payment
     */
    PaymentInitiateResponse subscribe(SubscribeRequest request) throws SubscriptionException, UserException, PaymentException;

    /**
     * Get active subscription for user
     */
    SubscriptionDTO getUsersActiveSubscription(Long userId) throws SubscriptionException, UserException;

    /**
     * Get all subscriptions for user
     */
    List<SubscriptionDTO> getUserSubscriptions(Long userId) throws SubscriptionException, UserException;

    /**
     * Renew subscription
     */
    PaymentInitiateResponse renewSubscription(Long subscriptionId, SubscribeRequest request) throws SubscriptionException, UserException, PaymentException;

    /**
     * Cancel subscription
     */
    SubscriptionDTO cancelSubscription(Long subscriptionId, String reason) throws SubscriptionException;

    SubscriptionCancellationEligibilityDTO getCancellationEligibility(Long subscriptionId) throws SubscriptionException;

    /**
     * Get subscription by ID
     */
    SubscriptionDTO getSubscriptionById(Long id) throws SubscriptionException;

    /**
     * Verify and activate subscription after successful payment
     */
    SubscriptionDTO activateSubscription(Long subscriptionId, Long paymentId) throws SubscriptionException;

    /**
     * Get all active subscriptions (Admin)
     */
    Page<SubscriptionDTO> getAllActiveSubscriptions(Pageable pageable);

    /**
     * Get all subscriptions with admin filters.
     */
    Page<SubscriptionDTO> getAdminSubscriptions(Pageable pageable, String search, String status, Long planId);

    /**
     * Deactivate expired subscriptions (Scheduler)
     */
    void deactivateExpiredSubscriptions();

    /**
     * Check if user has valid subscription
     */
    boolean hasValidSubscription(Long userId);
}
