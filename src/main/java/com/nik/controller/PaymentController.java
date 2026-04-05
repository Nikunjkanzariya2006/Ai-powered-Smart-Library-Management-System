package com.nik.controller;

import com.nik.domain.PaymentGateway;
import com.nik.domain.PaymentStatus;
import com.nik.domain.PaymentType;
import com.nik.exception.PaymentException;
import com.nik.payload.dto.PaymentDTO;
import com.nik.payload.request.PaymentFailureReportRequest;
import com.nik.payload.request.PaymentVerifyRequest;
import com.nik.payload.response.ApiResponse;
import com.nik.payload.response.RevenueStatisticsResponse;
import com.nik.service.PaymentService;
import com.nik.service.impl.PaymentServiceImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentServiceImpl paymentServiceImpl;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "createdAt", "updatedAt", "amount", "status", "paymentDate"
    );

    @PostMapping("/verify")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> verifyPayment(
            @Valid @RequestBody PaymentVerifyRequest request) {
        try {
            PaymentDTO payment = paymentService.verifyPayment(request);
            return ResponseEntity.ok(payment);
        } catch (PaymentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse(e.getMessage(), false));
        }
    }

    /**
     * Get all payments (Admin only)
     * GET /api/payments?page=0&size=10&sort=createdAt,desc
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<PaymentDTO>> getAllPayments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) PaymentGateway gateway,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) PaymentType paymentType) {

        Pageable pageable = createPageable(page, size, sortBy, sortDir);
        Page<PaymentDTO> payments = paymentService.getAllPayments(
                pageable,
                search,
                gateway,
                status,
                paymentType
        );
        return ResponseEntity.ok(payments);
    }

    /**
     * Explicitly mark a payment as failed using gateway reference.
     * This is a fallback endpoint for callback/status pages when gateway indicates failure.
     */
    @PostMapping("/fail")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> markPaymentFailed(@Valid @RequestBody PaymentFailureReportRequest request) {
        try {
            PaymentDTO payment = paymentService.markPaymentFailed(
                    request.getGatewayReference(),
                    request.getFailureReason()
            );
            return ResponseEntity.ok(payment);
        } catch (PaymentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ApiResponse(e.getMessage(), false));
        }
    }

    @PostMapping("/cancel/{paymentId}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> cancelPayment(@PathVariable Long paymentId) {
        try {
            PaymentDTO payment = paymentService.cancelPayment(paymentId);
            return ResponseEntity.ok(payment);
        } catch (PaymentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ApiResponse(e.getMessage(), false));
        }
    }

    @PostMapping("/sync/{paymentId}")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<?> syncPaymentStatus(@PathVariable Long paymentId) {
        try {
            PaymentDTO payment = paymentService.syncPaymentStatus(paymentId);
            return ResponseEntity.ok(payment);
        } catch (PaymentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ApiResponse(e.getMessage(), false));
        }
    }

    /**
     * Razorpay webhook endpoint
     * POST /api/payments/webhook/razorpay
     *
     * Razorpay sends payment notifications to this endpoint
     * Events: payment.captured, payment_link.paid, payment.failed
     */
    @PostMapping("/webhook/razorpay")
    public ResponseEntity<String> handleRazorpayWebhook(@RequestBody String webhookBody) {
        try {
            log.info("Received Razorpay webhook");

            // Parse webhook payload
            JSONObject webhookPayload = new JSONObject(webhookBody);

            // Process webhook
            paymentServiceImpl.handleRazorpayWebhook(webhookPayload);

            log.info("Razorpay webhook processed successfully");
            return ResponseEntity.ok("Webhook processed successfully");

        } catch (Exception e) {
            log.error("Error processing Razorpay webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Webhook processing failed");
        }
    }

    /**
     * Get revenue statistics for current month (Admin only)
     * GET /api/payments/statistics/monthly-revenue
     *
     * Returns total revenue for the current month from completed payments
     *
     * Example response:
     * {
     *   "monthlyRevenue": 15250.50,
     *   "currency": "USD",
     *   "year": 2025,
     *   "month": 10
     * }
     */
    @GetMapping("/statistics/monthly-revenue")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RevenueStatisticsResponse> getMonthlyRevenue() {
        RevenueStatisticsResponse stats = paymentService.getMonthlyRevenue();
        return ResponseEntity.ok(stats);
    }

    private Pageable createPageable(int page, int size, String sortBy, String sortDir) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(Math.min(size, 100), 1);
        String safeSortBy = ALLOWED_SORT_FIELDS.contains(sortBy) ? sortBy : "createdAt";
        String safeSortDir = sortDir == null ? "DESC" : sortDir;

        Sort sort = safeSortDir.equalsIgnoreCase("DESC")
                ? Sort.by(safeSortBy).descending()
                : Sort.by(safeSortBy).ascending();
        return PageRequest.of(safePage, safeSize, sort);
    }


}
