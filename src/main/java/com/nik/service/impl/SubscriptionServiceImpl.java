package com.nik.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.nik.domain.PaymentGateway;
import com.nik.domain.PaymentStatus;
import com.nik.domain.PaymentType;
import com.nik.domain.BookLoanStatus;
import com.nik.exception.AuthenticationFailureException;
import com.nik.exception.PaymentException;
import com.nik.exception.SubscriptionException;
import com.nik.exception.UserException;
import com.nik.model.BookLoan;
import com.nik.mapper.SubscriptionMapper;
import com.nik.model.Payment;
import com.nik.model.Subscription;
import com.nik.model.SubscriptionPlan;
import com.nik.model.User;
import com.nik.payload.dto.SubscriptionCancellationEligibilityDTO;
import com.nik.payload.dto.SubscriptionDTO;
import com.nik.payload.request.PaymentInitiateRequest;
import com.nik.payload.request.SubscribeRequest;
import com.nik.payload.response.PaymentInitiateResponse;
import com.nik.repository.BookLoanRepository;
import com.nik.repository.FineRepository;
import com.nik.repository.PaymentRepository;
import com.nik.repository.SubscriptionRepository;
import com.nik.repository.UserRepository;
import com.nik.service.PaymentService;
import com.nik.service.SubscriptionService;
import com.nik.service.UserService;

import jakarta.persistence.criteria.JoinType;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of SubscriptionService
 * Handles subscription creation, renewal, cancellation with payment integration
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class SubscriptionServiceImpl implements SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final BookLoanRepository bookLoanRepository;
    private final FineRepository fineRepository;
    private final PaymentRepository paymentRepository;
    private final SubscriptionMapper subscriptionMapper;
    private final UserService userService;
    private final PaymentService paymentService;
    private final com.nik.repository.SubscriptionPlanRepository subscriptionPlanRepository;

    @Override
    @Transactional
    public PaymentInitiateResponse subscribe(SubscribeRequest request)
            throws SubscriptionException, UserException, PaymentException {

        log.info("Processing subscription request for user: {}, plan ID: {}",
            request.getUserId(), request.getPlanId());

        User user = getCurrentAuthenticatedUser();
        SubscriptionPlan plan = subscriptionPlanRepository
                .findById(request.getPlanId())
            .orElseThrow(() -> new SubscriptionException("Subscription plan not found with ID: " + request.getPlanId()));

        // Validate plan is active
        if (!plan.getIsActive()) {
            throw new SubscriptionException("Subscription plan is not currently available: " + plan.getName());
        }

        Optional<Subscription> existingSubscription = subscriptionRepository
            .findActiveSubscriptionByUserId(user.getId(), LocalDate.now());

        if (existingSubscription.isPresent()) {
            throw new SubscriptionException(
                "User already has an active subscription. Please cancel it before subscribing to a new plan.");
        }

        Subscription subscription = new Subscription();
        subscription.setUser(user);
        subscription.setPlan(plan);
        subscription.setAutoRenew(request.getAutoRenew() != null ? request.getAutoRenew() : false);

        // Initialize from plan (sets price, maxBooks, maxDays, dates)
        subscription.initializeFromPlan();

        // Subscription starts as inactive until payment is confirmed
        subscription.setIsActive(false);

        subscription = subscriptionRepository.save(subscription);
        log.info("Subscription created with ID: {}", subscription.getId());

        PaymentInitiateRequest paymentInitiateRequest = PaymentInitiateRequest
                .builder()
                .userId(user.getId())
                .subscriptionId(subscription.getId())
                .paymentType(PaymentType.SUBSCRIPTION)
                .gateway(request.getPaymentGateway() == null ? PaymentGateway.RAZORPAY : request.getPaymentGateway())
                .amount(subscription.getPrice())
                .currency(subscription.getCurrency())
                .description("Library Subscription - " + plan.getName())
                .build();

        return paymentService
                .initiatePayment(paymentInitiateRequest);
    }

    @Override
    public SubscriptionDTO activateSubscription(Long subscriptionId, Long paymentId)
            throws SubscriptionException {

        log.info("Activating subscription: {} after payment: {}", subscriptionId, paymentId);

        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new SubscriptionException("Subscription not found with ID: " + subscriptionId));

        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new SubscriptionException("Payment not found with ID: " + paymentId));

        // Verify payment is successful
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new SubscriptionException(
                "Cannot activate subscription. Payment status is: " + payment.getStatus());
        }

        // Verify payment belongs to this subscription
        if (!payment.getSubscription().getId().equals(subscriptionId)) {
            throw new SubscriptionException("Payment does not belong to this subscription");
        }

        // Activate subscription
        subscription.setIsActive(true);

        // Ensure start date is set
        if (subscription.getStartDate() == null 
        || subscription.getStartDate().isBefore(LocalDate.now())) {
            subscription.setStartDate(LocalDate.now());
            subscription.calculateEndDate();
        }

        subscription = subscriptionRepository.save(subscription);

        log.info("Subscription activated successfully: {}", subscriptionId);
        return subscriptionMapper.toDTO(subscription);
    }

    @Override
    public SubscriptionDTO getUsersActiveSubscription(Long userId)
            throws SubscriptionException, UserException {

        if(userId!=null){
            if (!userRepository.existsById(userId)) {
                throw new SubscriptionException("User not found with ID: " + userId);
            }
        }
        else{
            User user=userService.getCurrentUser();
            userId=user.getId();
        }

        return subscriptionRepository
            .findActiveSubscriptionByUserId(userId, LocalDate.now())
            .map(subscriptionMapper::toDTO)
            .orElse(null);
    }

    @Override
    public List<SubscriptionDTO> getUserSubscriptions(Long userId)
            throws SubscriptionException, UserException {



        if(userId!=null){
            if (!userRepository.existsById(userId)) {
                throw new SubscriptionException("User not found with ID: " + userId);
            }
        }else{
            User user=userService.getCurrentUser();
            userId=user.getId();
        }


        List<Subscription> subscriptions = subscriptionRepository
            .findByUserIdOrderByCreatedAtDesc(userId);

        return subscriptions.stream().map(subscriptionMapper::toDTO).toList();
    }

    @Override
    public PaymentInitiateResponse renewSubscription(Long subscriptionId, SubscribeRequest request)
            throws SubscriptionException, UserException, PaymentException {

        log.info("Renewing subscription: {}", subscriptionId);

        Subscription oldSubscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new SubscriptionException(
                "Subscription not found with ID: " + subscriptionId));

        // Cancel old subscription if still active
        if (oldSubscription.getIsActive()) {
            oldSubscription.setIsActive(false);
            oldSubscription.setCancelledAt(LocalDateTime.now());
            oldSubscription.setCancellationReason("Renewed to new subscription");
            subscriptionRepository.save(oldSubscription);
        }

        // Create new subscription with same or different plan
        request.setUserId(oldSubscription.getUser().getId());
        if (request.getPlanId() == null) {
            request.setPlanId(oldSubscription.getPlan().getId());
        }

        return subscribe(request);
    }

    @Override
    public SubscriptionDTO cancelSubscription(Long subscriptionId, String reason)
            throws SubscriptionException {

        log.info("Cancelling subscription: {} with reason: {}", subscriptionId, reason);

        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new SubscriptionException(
                "Subscription not found with ID: " + subscriptionId));

        if (!subscription.getIsActive()) {
            throw new SubscriptionException("Subscription is already inactive");
        }

        SubscriptionCancellationEligibilityDTO eligibility = buildCancellationEligibility(subscription);
        if (!eligibility.isCanCancel()) {
            throw new SubscriptionException(eligibility.getMessage());
        }

        // Mark as cancelled
        subscription.setIsActive(false);
        subscription.setCancelledAt(LocalDateTime.now());
        subscription.setCancellationReason(reason != null ? reason : "Cancelled by user");

        subscription = subscriptionRepository.save(subscription);

        log.info("Subscription cancelled successfully: {}", subscriptionId);
        return subscriptionMapper.toDTO(subscription);
    }

    @Override
    public SubscriptionCancellationEligibilityDTO getCancellationEligibility(Long subscriptionId)
            throws SubscriptionException {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new SubscriptionException(
                        "Subscription not found with ID: " + subscriptionId));

        return buildCancellationEligibility(subscription);
    }

    @Override
    public SubscriptionDTO getSubscriptionById(Long id) throws SubscriptionException {
        Subscription subscription = subscriptionRepository.findById(id)
            .orElseThrow(() -> new SubscriptionException("Subscription not found with ID: " + id));

        return subscriptionMapper.toDTO(subscription);
    }

    @Override
    public Page<SubscriptionDTO> getAllActiveSubscriptions(Pageable pageable) {
        Page<Subscription> subscriptions = subscriptionRepository
            .findAllActiveSubscriptions(LocalDate.now(), pageable);

        return subscriptions.map(subscriptionMapper::toDTO);
    }

    @Override
    public Page<SubscriptionDTO> getAdminSubscriptions(Pageable pageable, String search, String status, Long planId) {
        Specification<Subscription> specification = Specification.where(null);
        LocalDate today = LocalDate.now();

        if (planId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("plan").get("id"), planId));
        }

        if (search != null && !search.trim().isEmpty()) {
            String normalizedSearch = search.trim().toLowerCase();
            specification = specification.and((root, query, cb) -> {
                var userJoin = root.join("user", JoinType.LEFT);
                List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

                predicates.add(cb.like(cb.lower(userJoin.get("fullName")), "%" + normalizedSearch + "%"));
                predicates.add(cb.like(cb.lower(userJoin.get("email")), "%" + normalizedSearch + "%"));
                predicates.add(cb.like(cb.lower(root.get("planName")), "%" + normalizedSearch + "%"));
                predicates.add(cb.like(cb.lower(root.get("planCode")), "%" + normalizedSearch + "%"));

                if (normalizedSearch.matches("\\d+")) {
                    predicates.add(cb.equal(root.get("id"), Long.parseLong(normalizedSearch)));
                }

                return cb.or(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
            });
        }

        if (status != null && !status.trim().isEmpty()) {
            String normalizedStatus = status.trim().toUpperCase();
            specification = specification.and((root, query, cb) -> switch (normalizedStatus) {
                case "ACTIVE" -> cb.and(
                        cb.isTrue(root.get("isActive")),
                        cb.lessThanOrEqualTo(root.get("startDate"), today),
                        cb.greaterThanOrEqualTo(root.get("endDate"), today),
                        cb.isNull(root.get("cancelledAt"))
                );
                case "EXPIRED" -> cb.and(
                        cb.lessThan(root.get("endDate"), today),
                        cb.isNull(root.get("cancelledAt"))
                );
                case "CANCELLED" -> cb.isNotNull(root.get("cancelledAt"));
                default -> cb.conjunction();
            });
        }

        Page<Subscription> subscriptions = subscriptionRepository.findAll(specification, pageable);
        return subscriptions.map(subscriptionMapper::toDTO);
    }

    @Override
    public void deactivateExpiredSubscriptions() {
        log.info("Running subscription expiry check at {}", LocalDateTime.now());

        List<Subscription> expiredSubscriptions = subscriptionRepository
            .findExpiredActiveSubscriptions(LocalDate.now());

        int deactivatedCount = 0;
        for (Subscription subscription : expiredSubscriptions) {
            subscription.setIsActive(false);
            subscription.setNotes(
                (subscription.getNotes() != null ? subscription.getNotes() + "\n" : "") +
                "Auto-deactivated on " + LocalDate.now() + " due to expiry");
            subscriptionRepository.save(subscription);
            deactivatedCount++;

            log.debug("Deactivated expired subscription ID: {} for user: {}",
                subscription.getId(), subscription.getUser().getEmail());
        }

        log.info("Deactivated {} expired subscriptions", deactivatedCount);
    }

    @Override
    public boolean hasValidSubscription(Long userId) {
        return subscriptionRepository
        .hasActiveSubscription(userId, LocalDate.now());
    }
    private User getCurrentAuthenticatedUser() throws UserException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationFailureException("User is not authenticated");
        }

        String email = authentication.getName();
        User user = userRepository.findByEmail(email);

        if (user == null) {
            throw new AuthenticationFailureException("Authenticated user could not be resolved");
        }

        return user;
    }

    private SubscriptionCancellationEligibilityDTO buildCancellationEligibility(Subscription subscription) {
        List<String> blockingStatuses = new ArrayList<>();

        long activeOrOverdueCount = bookLoanRepository.countByUserIdAndStatusIn(
                subscription.getUser().getId(),
                List.of(BookLoanStatus.CHECKED_OUT, BookLoanStatus.OVERDUE)
        );
        if (activeOrOverdueCount > 0) {
            blockingStatuses.add("ACTIVE");
        }
        if (bookLoanRepository.countByUserIdAndStatus(subscription.getUser().getId(), BookLoanStatus.DAMAGED) > 0) {
            blockingStatuses.add("DAMAGED");
        }

        List<BookLoan> lostLoans = bookLoanRepository.findByUserIdAndStatusIn(
                subscription.getUser().getId(),
                List.of(BookLoanStatus.LOST)
        );
        boolean hasLostLoanWithUnpaidFine = lostLoans.stream()
                .anyMatch(loan -> fineRepository.hasUnpaidFineForBookLoan(loan.getId()));
        if (hasLostLoanWithUnpaidFine) {
            blockingStatuses.add("LOST_UNPAID_FINE");
        }

        if (!blockingStatuses.isEmpty()) {
            return new SubscriptionCancellationEligibilityDTO(
                    false,
                    blockingStatuses,
                    "Please return active or damaged books, and clear unpaid fines on lost books before cancelling the subscription."
            );
        }

        return new SubscriptionCancellationEligibilityDTO(
                true,
                List.of(),
                "Subscription can be cancelled."
        );
    }
}


