package com.nik.service.impl;

import com.nik.domain.PaymentGateway;
import com.nik.domain.PaymentStatus;
import com.nik.event.PaymentFailedEvent;
import com.nik.event.PaymentInitiatedEvent;
import com.nik.event.PaymentSuccessEvent;
import com.nik.event.publisher.PaymentEventPublisher;
import com.nik.exception.PaymentException;
import com.nik.mapper.PaymentMapper;
import com.nik.model.*;
import com.nik.domain.PaymentType;
import com.nik.payload.dto.PaymentDTO;
import com.nik.payload.request.PaymentInitiateRequest;
import com.nik.payload.request.PaymentVerifyRequest;
import com.nik.payload.response.PaymentInitiateResponse;
import com.nik.payload.response.RevenueStatisticsResponse;
import com.nik.repository.*;
import com.nik.service.PaymentService;
import com.nik.service.gateway.RazorpayService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.json.JSONArray;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import com.nik.payload.response.PaymentLinkResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.criteria.JoinType;

/**
 * Implementation of PaymentService
 * Handles payment processing with Razorpay
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final BookLoanRepository bookLoanRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentMapper paymentMapper;
    private final RazorpayService razorpayService;
    private final FineRepository fineRepository;
    private final PaymentEventPublisher paymentEventPublisher;


    @Override
    public PaymentInitiateResponse initiatePayment(
            PaymentInitiateRequest request) throws PaymentException {
        PaymentGateway gateway = request.getGateway() == null ? PaymentGateway.RAZORPAY : request.getGateway();
        log.info("Initiating payment for user: {}, type: {}, gateway: {}",
                request.getUserId(), request.getPaymentType(), gateway);

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new PaymentException("User not found with ID: " + request.getUserId()));

        Payment payment = new Payment();
        payment.setUser(user);
        payment.setPaymentType(request.getPaymentType());
        payment.setGateway(gateway);
        payment.setAmount(request.getAmount());
        payment.setCurrency(request.getCurrency() != null ? request.getCurrency() : "INR");
        payment.setDescription(request.getDescription());
        payment.setStatus(PaymentStatus.PENDING);
        payment.setTransactionId("TXN_" + UUID.randomUUID());
        payment.setInitiatedAt(LocalDateTime.now());

        if (request.getSubscriptionId() != null) {
            Subscription sub = subscriptionRepository
                    .findById(request.getSubscriptionId())
                    .orElseThrow(() -> new PaymentException("Subscription not found"));
            payment.setSubscription(sub);
        }

        if (request.getBookLoanId() != null) {
            BookLoan loan = bookLoanRepository.findById(request.getBookLoanId())
                    .orElseThrow(() -> new PaymentException("Book loan not found"));
            payment.setBookLoan(loan);
        }

        if (request.getFineId() != null) {
            Fine fine = fineRepository.findById(request.getFineId())
                    .orElseThrow(() -> new PaymentException("Fine not found"));
            payment.setFine(fine);
        }

        payment = paymentRepository.save(payment);

        PaymentInitiateResponse response;
        try {
            PaymentLinkResponse linkResponse = razorpayService.createPaymentLink(user, payment);

            response = PaymentInitiateResponse.builder()
                    .paymentId(payment.getId())
                    .gateway(payment.getGateway())
                    .checkoutUrl(linkResponse.getPayment_link_url())
                    .transactionId(linkResponse.getPayment_link_id())
                    .amount(payment.getAmount())
                    .currency(payment.getCurrency())
                    .description(payment.getDescription())
                    .success(true)
                    .message("Payment initiated successfully")
                    .build();

            payment.setGatewayOrderId(linkResponse.getPayment_link_id());

            payment.setStatus(PaymentStatus.PROCESSING);
            payment = paymentRepository.save(payment);

            publishPaymentInitiatedEvent(payment, response.getCheckoutUrl());

            return response;
        } catch (Exception ex) {
            String failureReason = ex.getMessage() != null ? ex.getMessage() : "Payment initiation failed";
            payment = markPaymentAsFailed(payment, failureReason);

            if (ex instanceof PaymentException paymentException) {
                throw paymentException;
            }
            throw new PaymentException("Failed to initiate payment: " + failureReason, ex);
        }
    }

    @Override
    public PaymentDTO verifyPayment(PaymentVerifyRequest request) throws PaymentException {
        JSONObject paymentDetails = razorpayService
                .fetchPaymentDetails(request.getRazorpayPaymentId());

        log.debug("Gateway payment details received for {}", request.getRazorpayPaymentId());

        JSONObject notes = paymentDetails.getJSONObject("notes");
        Long paymentId = Long.parseLong(notes.optString("payment_id"));


        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentException("Payment not found with ID: " + paymentId));

        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            log.info("Skipping duplicate payment verification for already completed payment: {}", payment.getId());
            return paymentMapper.toDTO(payment);
        }

        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            throw new PaymentException("Payment was cancelled before completion");
        }

        String gatewayStatus = paymentDetails.optString("status");
        if ("failed".equalsIgnoreCase(gatewayStatus)) {
            log.error("Payment verification received failed gateway status for payment: {}", payment.getId());
            payment = markPaymentAsFailed(
                    payment,
                    paymentDetails.optString("error_description", "Payment failed")
            );
            return paymentMapper.toDTO(payment);
        }

        if (!"captured".equalsIgnoreCase(gatewayStatus)) {
            throw new PaymentException("Payment is not completed yet. Current gateway status: " + gatewayStatus);
        }

        razorpayService.validateCapturedPayment(paymentDetails);

        payment.setGatewayPaymentId(request.getRazorpayPaymentId());
        payment.setGatewayOrderId(request.getRazorpayOrderId());
        payment.setGatewaySignature(request.getRazorpaySignature());
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setCompletedAt(LocalDateTime.now());
        log.info("Payment verified successfully: {}", payment.getId());

        payment = paymentRepository.save(payment);
        publishPaymentSuccessEvent(payment);

        return paymentMapper.toDTO(payment);
    }

    @Override
    public PaymentDTO getPaymentById(Long paymentId) throws PaymentException {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentException("Payment not found with ID: " + paymentId));
        return paymentMapper.toDTO(payment);
    }

    @Override
    public PaymentDTO getPaymentByTransactionId(String transactionId) throws PaymentException {
        Payment payment = paymentRepository.findByTransactionId(transactionId)
            .orElseThrow(() -> new PaymentException("Payment not found with transaction ID: " + transactionId));
        return paymentMapper.toDTO(payment);
    }

    @Override
    public Page<PaymentDTO> getUserPayments(Long userId, Pageable pageable) throws PaymentException {
        if (!userRepository.existsById(userId)) {
            throw new PaymentException("User not found with ID: " + userId);
        }
        Page<Payment> payments = paymentRepository.findByUserIdAndActiveTrue(userId, pageable);
        return payments.map(this::toNormalizedPaymentDTO);
    }

    @Override
    public Page<PaymentDTO> getAllPayments(Pageable pageable) {
        Page<Payment> payments = paymentRepository.findAll(pageable);
        return payments.map(this::toNormalizedPaymentDTO);
    }

    @Override
    public Page<PaymentDTO> getAllPayments(
            Pageable pageable,
            String search,
            PaymentGateway gateway,
            PaymentStatus status,
            PaymentType paymentType
    ) {
        Specification<Payment> specification = Specification.where(isActivePayment());

        if (gateway != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("gateway"), gateway));
        }

        if (status != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }

        if (paymentType != null) {
            specification = specification.and(buildPaymentTypeSpecification(paymentType));
        }

        if (search != null && !search.trim().isEmpty()) {
            String normalizedSearch = "%" + search.trim().toLowerCase() + "%";
            specification = specification.and((root, query, cb) -> {
                var userJoin = root.join("user", JoinType.LEFT);
                return cb.or(
                        cb.like(cb.lower(root.get("transactionId")), normalizedSearch),
                        cb.like(cb.lower(root.get("gatewayPaymentId")), normalizedSearch),
                        cb.like(cb.lower(root.get("gatewayOrderId")), normalizedSearch),
                        cb.like(cb.lower(root.get("description")), normalizedSearch),
                        cb.like(cb.lower(userJoin.get("fullName")), normalizedSearch),
                        cb.like(cb.lower(userJoin.get("email")), normalizedSearch)
                );
            });
        }

        Page<Payment> payments = paymentRepository.findAll(specification, pageable);
        return payments.map(this::toNormalizedPaymentDTO);
    }

    @Override
    public PaymentDTO cancelPayment(Long paymentId) throws PaymentException {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentException("Payment not found with ID: " + paymentId));

        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            return paymentMapper.toDTO(payment);
        }

        if (!payment.isPending()) {
            throw new PaymentException("Only pending payments can be cancelled");
        }

        payment.setStatus(PaymentStatus.CANCELLED);
        payment.setFailureReason("Payment cancelled before completion");
        payment = paymentRepository.save(payment);

        log.info("Payment cancelled: {}", paymentId);
        return paymentMapper.toDTO(payment);
    }

    @Override
    public PaymentInitiateResponse retryPayment(Long paymentId) throws PaymentException {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentException("Payment not found with ID: " + paymentId));

        if (!payment.canRetry()) {
            throw new PaymentException("Payment cannot be retried. Max retry attempts reached or payment is not in failed/cancelled state");
        }

        PaymentInitiateRequest request = new PaymentInitiateRequest();
        request.setUserId(payment.getUser().getId());
        request.setBookLoanId(payment.getBookLoan() != null ? payment.getBookLoan().getId() : null);
        request.setPaymentType(payment.getPaymentType());
        request.setGateway(payment.getGateway());
        request.setAmount(payment.getAmount());
        request.setCurrency(payment.getCurrency());
        request.setDescription(payment.getDescription());

        payment.setRetryCount(payment.getRetryCount() + 1);
        paymentRepository.save(payment);

        log.info("Retrying payment: {}, Retry count: {}", paymentId, payment.getRetryCount());
        return initiatePayment(request);
    }

    @Override
    public PaymentDTO markPaymentFailed(String gatewayReference, String failureReason) throws PaymentException {
        if (gatewayReference == null || gatewayReference.isBlank()) {
            throw new PaymentException("Gateway reference is required");
        }
        String normalizedReference = gatewayReference.trim();

        Payment payment = paymentRepository.findByGatewayOrderId(normalizedReference)
                .or(() -> paymentRepository.findByGatewayPaymentId(normalizedReference))
                .or(() -> paymentRepository.findByTransactionId(normalizedReference))
                .orElseThrow(() -> new PaymentException("Payment not found for reference: " + normalizedReference));

        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            throw new PaymentException("Payment already marked as success, cannot mark as failed");
        }

        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            return paymentMapper.toDTO(payment);
        }

        payment = markPaymentAsFailed(payment, failureReason);

        return paymentMapper.toDTO(payment);
    }

    @Override
    public PaymentDTO syncPaymentStatus(Long paymentId) throws PaymentException {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException("Payment not found with ID: " + paymentId));

        if (payment.getStatus() == PaymentStatus.SUCCESS
                || payment.getStatus() == PaymentStatus.FAILED
                || payment.getStatus() == PaymentStatus.CANCELLED) {
            return paymentMapper.toDTO(payment);
        }

        if (payment.getGatewayOrderId() == null || payment.getGatewayOrderId().isBlank()) {
            throw new PaymentException("Gateway payment link is not available for synchronization");
        }

        JSONObject paymentLink = razorpayService.fetchPaymentLinkDetails(payment.getGatewayOrderId());
        String paymentLinkStatus = paymentLink.optString("status");
        String gatewayOrderId = paymentLink.optString("order_id");

        if (!gatewayOrderId.isBlank()) {
            JSONArray payments = razorpayService.fetchOrderPayments(gatewayOrderId);
            JSONObject latestAttempt = findLatestPaymentAttempt(payments);

            if (latestAttempt != null) {
                String latestStatus = latestAttempt.optString("status");
                if ("captured".equalsIgnoreCase(latestStatus)) {
                    payment.setGatewayPaymentId(latestAttempt.optString("id", payment.getGatewayPaymentId()));
                    payment.setGatewayOrderId(gatewayOrderId);
                    if (payment.getPaymentMethod() == null || payment.getPaymentMethod().isBlank()) {
                        payment.setPaymentMethod(latestAttempt.optString("method", payment.getPaymentMethod()));
                    }
                    payment.setStatus(PaymentStatus.SUCCESS);
                    payment.setCompletedAt(LocalDateTime.now());
                    payment = paymentRepository.save(payment);
                    publishPaymentSuccessEvent(payment);
                    return paymentMapper.toDTO(payment);
                }

                if ("failed".equalsIgnoreCase(latestStatus)) {
                    payment = markPaymentAsFailed(
                            payment,
                            latestAttempt.optString("error_description", "Payment failed")
                    );
                    return paymentMapper.toDTO(payment);
                }
            }
        }

        if ("cancelled".equalsIgnoreCase(paymentLinkStatus) || "expired".equalsIgnoreCase(paymentLinkStatus)) {
            payment.setStatus(PaymentStatus.CANCELLED);
            payment.setFailureReason("Payment link " + paymentLinkStatus);
            payment = paymentRepository.save(payment);
        }

        return paymentMapper.toDTO(payment);
    }

    @Override
    public RevenueStatisticsResponse getMonthlyRevenue() {
        LocalDateTime now = LocalDateTime.now();
        int currentYear = now.getYear();
        int currentMonth = now.getMonthValue();
        LocalDateTime startOfMonth = now.withDayOfMonth(1).toLocalDate().atStartOfDay();
        LocalDateTime startOfNextMonth = startOfMonth.plusMonths(1);

        Long totalRevenueMinorUnits = paymentRepository.sumAmountByStatusAndCompletedAtBetween(
                PaymentStatus.SUCCESS,
                startOfMonth,
                startOfNextMonth
        );
        double totalRevenue = totalRevenueMinorUnits == null ? 0.0 : totalRevenueMinorUnits.doubleValue();
        String currency = "INR";

        // Build response
        RevenueStatisticsResponse response = new RevenueStatisticsResponse();
        response.setMonthlyRevenue(totalRevenue);
        response.setCurrency(currency);
        response.setYear(currentYear);
        response.setMonth(currentMonth);

        return response;
    }


    /**
     * Handle Razorpay webhook for payment updates
     * Called when Razorpay sends webhook notifications for payment events
     */
    public void handleRazorpayWebhook(JSONObject webhookPayload) {
        try {
            String event = webhookPayload.getString("event");
            log.info("Processing Razorpay webhook event: {}", event);

            if ("payment.captured".equals(event) || "payment_link.paid".equals(event)) {
                JSONObject payload = webhookPayload.getJSONObject("payload");
                JSONObject paymentEntity = payload.getJSONObject("payment").getJSONObject("entity");

                String gatewayPaymentId = paymentEntity.getString("id");

                // Try to find payment by transaction ID or notes
                Payment payment = null;

                if (paymentEntity.has("notes")) {
                    JSONObject notes = paymentEntity.getJSONObject("notes");
                    if (notes.has("payment_id")) {
                        Long paymentId = notes.getLong("payment_id");
                        payment = paymentRepository.findById(paymentId).orElse(null);
                    }
                }

                if (payment != null && payment.getStatus() != PaymentStatus.SUCCESS) {
                    payment.setStatus(PaymentStatus.SUCCESS);
                    payment.setCompletedAt(LocalDateTime.now());
                    payment.setGatewayPaymentId(gatewayPaymentId);

                    // Extract payment method
                    if (paymentEntity.has("method")) {
                        payment.setPaymentMethod(paymentEntity.getString("method"));
                    }

                    payment = paymentRepository.save(payment);
                    log.info("Payment {} marked as successful via webhook", payment.getId());

                    // Publish payment success event (instead of direct service calls)
                    publishPaymentSuccessEvent(payment);
                }
            } else if ("payment.failed".equals(event)) {
                // Handle failed payment
                JSONObject payload = webhookPayload.getJSONObject("payload");
                JSONObject paymentEntity = payload.getJSONObject("payment").getJSONObject("entity");

                if (paymentEntity.has("notes")) {
                    JSONObject notes = paymentEntity.getJSONObject("notes");
                    if (notes.has("payment_id")) {
                        Long paymentId = notes.getLong("payment_id");
                        Payment payment = paymentRepository.findById(paymentId).orElse(null);

                        if (payment != null
                                && payment.getStatus() != PaymentStatus.SUCCESS
                                && payment.getStatus() != PaymentStatus.FAILED
                                && payment.getStatus() != PaymentStatus.CANCELLED) {
                            payment = markPaymentAsFailed(
                                    payment,
                                    paymentEntity.optString("error_description", "Payment failed")
                            );
                            log.info("Payment {} marked as failed via webhook", payment.getId());
                        }
                    }
                }
            } else if ("payment_link.cancelled".equals(event) || "payment_link.expired".equals(event)) {
                JSONObject payload = webhookPayload.getJSONObject("payload");
                JSONObject linkEntity = payload.getJSONObject("payment_link").getJSONObject("entity");
                String paymentLinkId = linkEntity.optString("id");

                if (!paymentLinkId.isBlank()) {
                    Payment payment = paymentRepository.findByGatewayOrderId(paymentLinkId).orElse(null);
                    if (payment != null && payment.getStatus() != PaymentStatus.SUCCESS) {
                        payment.setStatus(PaymentStatus.CANCELLED);
                        payment.setFailureReason("Payment link " + linkEntity.optString("status", "cancelled"));
                        paymentRepository.save(payment);
                        log.info("Payment {} marked as cancelled via payment link webhook", payment.getId());
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error processing Razorpay webhook: {}", e.getMessage(), e);
        }
    }

    /**
     * Publish payment initiated event to notify other services.
     * This can be used for tracking and sending initial notifications.
     *
     * @param payment The initiated payment
     * @param checkoutUrl The URL for user to complete payment
     */
    private void publishPaymentInitiatedEvent(Payment payment, String checkoutUrl) {
        PaymentInitiatedEvent event = PaymentInitiatedEvent.builder()
            .paymentId(payment.getId())
            .userId(payment.getUser().getId())
            .paymentType(payment.getPaymentType())
            .gateway(payment.getGateway())
            .amount(payment.getAmount())
            .currency(payment.getCurrency())
            .subscriptionId(payment.getSubscription() != null ? payment.getSubscription().getId() : null)
            .fineId(payment.getFine() != null ? payment.getFine().getId() : null)
            .bookLoanId(payment.getBookLoan() != null ? payment.getBookLoan().getId() : null)
            .transactionId(payment.getTransactionId())
            .initiatedAt(payment.getInitiatedAt())
            .description(payment.getDescription())
            .checkoutUrl(checkoutUrl)
            .userEmail(payment.getUser().getEmail())
            .userName(payment.getUser().getFullName())
            .build();

        paymentEventPublisher.publishPaymentInitiated(event);
    }

    /**
     * Publish payment success event to notify other services.
     * This decouples payment processing from domain-specific actions.
     *
     * @param payment The successful payment
     */
    private void publishPaymentSuccessEvent(Payment payment) {
        PaymentSuccessEvent event = PaymentSuccessEvent.builder()
            .paymentId(payment.getId())
            .userId(payment.getUser().getId())
            .paymentType(payment.getPaymentType())
            .amount(payment.getAmount())
            .currency(payment.getCurrency())
            .subscriptionId(payment.getSubscription() != null ? payment.getSubscription().getId() : null)
            .fineId(payment.getFine() != null ? payment.getFine().getId() : null)
            .bookLoanId(payment.getBookLoan() != null ? payment.getBookLoan().getId() : null)
            .gatewayPaymentId(payment.getGatewayPaymentId())
            .transactionId(payment.getTransactionId())
            .completedAt(payment.getCompletedAt())
            .description(payment.getDescription())
            .build();

        paymentEventPublisher.publishPaymentSuccess(event);
    }

    /**
     * Publish payment failed event to notify other services.
     * This allows services to react to failures (e.g., send notifications, log errors).
     *
     * @param payment The failed payment
     */
    private void publishPaymentFailedEvent(Payment payment) {
        PaymentFailedEvent event = PaymentFailedEvent.builder()
            .paymentId(payment.getId())
            .userId(payment.getUser().getId())
            .paymentType(payment.getPaymentType())
            .amount(payment.getAmount())
            .currency(payment.getCurrency())
            .subscriptionId(payment.getSubscription() != null ? payment.getSubscription().getId() : null)
            .fineId(payment.getFine() != null ? payment.getFine().getId() : null)
            .bookLoanId(payment.getBookLoan() != null ? payment.getBookLoan().getId() : null)
            .failureReason(payment.getFailureReason())
            .gatewayPaymentId(payment.getGatewayPaymentId())
            .transactionId(payment.getTransactionId())
            .failedAt(LocalDateTime.now())
            .description(payment.getDescription())
            .userEmail(payment.getUser().getEmail())
            .userName(payment.getUser().getFullName())
            .build();

        paymentEventPublisher.publishPaymentFailed(event);
    }

    /**
     * Centralized failed-state transition.
     * Keeps transitions idempotent and guarantees failure event publication exactly once per transition.
     */
    private Payment markPaymentAsFailed(Payment payment, String failureReason) {
        String resolvedReason = (failureReason == null || failureReason.isBlank())
                ? "Payment failed at gateway"
                : failureReason;

        if (payment.getStatus() == PaymentStatus.FAILED) {
            if (payment.getFailureReason() == null || payment.getFailureReason().isBlank()) {
                payment.setFailureReason(resolvedReason);
                return paymentRepository.save(payment);
            }
            return payment;
        }

        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            return payment;
        }

        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason(resolvedReason);
        Payment savedPayment = paymentRepository.save(payment);
        publishPaymentFailedEvent(savedPayment);
        return savedPayment;
    }

    private Specification<Payment> isActivePayment() {
        return (root, query, cb) -> cb.isTrue(root.get("active"));
    }

    private Specification<Payment> buildPaymentTypeSpecification(PaymentType paymentType) {
        return (root, query, cb) -> {
            if (paymentType == PaymentType.SUBSCRIPTION) {
                return root.get("paymentType").in(PaymentType.SUBSCRIPTION);
            }
            return cb.equal(root.get("paymentType"), paymentType);
        };
    }

    private PaymentDTO toNormalizedPaymentDTO(Payment payment) {
        PaymentDTO paymentDTO = paymentMapper.toDTO(payment);
        return paymentDTO;
    }

    private JSONObject findLatestPaymentAttempt(JSONArray payments) {
        JSONObject latestAttempt = null;
        long latestCreatedAt = Long.MIN_VALUE;

        for (int i = 0; i < payments.length(); i++) {
            JSONObject candidate = payments.optJSONObject(i);
            if (candidate == null) {
                continue;
            }

            long createdAt = candidate.optLong("created_at", Long.MIN_VALUE);
            if (latestAttempt == null || createdAt >= latestCreatedAt) {
                latestAttempt = candidate;
                latestCreatedAt = createdAt;
            }
        }

        return latestAttempt;
    }

    public void reconcileStaleProcessingPayments() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(30);
        List<Payment> stalePayments = paymentRepository
                .findByStatusAndActiveTrueAndUpdatedAtBefore(PaymentStatus.PROCESSING, cutoff);

        for (Payment payment : stalePayments) {
            try {
                syncPaymentStatus(payment.getId());
            } catch (Exception e) {
                log.warn("Failed to reconcile payment {}: {}", payment.getId(), e.getMessage());
            }
        }
    }
}

