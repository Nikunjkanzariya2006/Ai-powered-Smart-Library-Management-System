package com.nik.service;

import com.nik.domain.PaymentGateway;
import com.nik.domain.PaymentStatus;
import com.nik.domain.PaymentType;
import com.nik.exception.PaymentException;
import com.nik.payload.dto.PaymentDTO;
import com.nik.payload.request.PaymentInitiateRequest;
import com.nik.payload.request.PaymentVerifyRequest;
import com.nik.payload.response.PaymentInitiateResponse;
import com.nik.payload.response.RevenueStatisticsResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service interface for payment operations
 * Handles payment initiation, verification, and management
 */
public interface PaymentService {

    /**
     * Initiate a new payment (creates order with payment gateway)
     */
    PaymentInitiateResponse initiatePayment(PaymentInitiateRequest request) throws PaymentException;

    /**
     * Verify payment after gateway callback
     */
    PaymentDTO verifyPayment(PaymentVerifyRequest request) throws PaymentException;

    /**
     * Get payment by ID
     */
    PaymentDTO getPaymentById(Long paymentId) throws PaymentException;

    /**
     * Get payment by transaction ID
     */
    PaymentDTO getPaymentByTransactionId(String transactionId) throws PaymentException;

    /**
     * Get all payments for a user
     */
    Page<PaymentDTO> getUserPayments(Long userId, Pageable pageable) throws PaymentException;

    /**
     * Get all payments (admin)
     */
    Page<PaymentDTO> getAllPayments(Pageable pageable);

    /**
     * Get all payments with optional admin filters.
     */
    Page<PaymentDTO> getAllPayments(
            Pageable pageable,
            String search,
            PaymentGateway gateway,
            PaymentStatus status,
            PaymentType paymentType
    );

    /**
     * Cancel a pending payment
     */
    PaymentDTO cancelPayment(Long paymentId) throws PaymentException;

    /**
     * Retry a failed payment
     */
    PaymentInitiateResponse retryPayment(Long paymentId) throws PaymentException;

    /**
     * Mark payment as failed using gateway reference and trigger failure event.
     */
    PaymentDTO markPaymentFailed(String gatewayReference, String failureReason) throws PaymentException;

    /**
     * Synchronize the payment status with the gateway using the latest payment-link/order state.
     */
    PaymentDTO syncPaymentStatus(Long paymentId) throws PaymentException;

    /**
     * Get monthly revenue statistics (Admin only)
     */
    RevenueStatisticsResponse getMonthlyRevenue();
}
