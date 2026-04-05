package com.nik.controller;

import com.nik.payload.dto.SubscriptionPlanDTO;
import com.nik.payload.response.ApiResponse;
import com.nik.service.SubscriptionPlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for subscription plan management
 */
@RestController
@RequestMapping("/api/subscription-plans")
@RequiredArgsConstructor
@Slf4j
public class SubscriptionPlanController {

    private final SubscriptionPlanService planService;

    /**
     * Get all active subscription plans (Public)
     * GET /api/subscription-plans/active
     */
    @GetMapping("/active")
    public ResponseEntity<List<SubscriptionPlanDTO>> getAllActivePlans() {
        List<SubscriptionPlanDTO> plans = planService.getAllActivePlans();
        return ResponseEntity.ok(plans);
    }

    /**
     * Get all active subscription plans with pagination (Public)
     * GET /api/subscription-plans/active/paginated?page=0&size=10
     */
    @GetMapping("/active/paginated")
    public ResponseEntity<Page<SubscriptionPlanDTO>> getAllActivePlansPaginated(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<SubscriptionPlanDTO> plans = planService.getAllActivePlans(pageable);
        return ResponseEntity.ok(plans);
    }

    /**
     * Get featured subscription plans (Public)
     * GET /api/subscription-plans/featured
     */
    @GetMapping("/featured")
    public ResponseEntity<List<SubscriptionPlanDTO>> getFeaturedPlans() {
        List<SubscriptionPlanDTO> plans = planService.getFeaturedPlans();
        return ResponseEntity.ok(plans);
    }

    /**
     * Create new subscription plan (Admin only)
     * POST /api/subscription-plans/admin/create
     */
    @PostMapping("/admin/create")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SubscriptionPlanDTO> createPlan(@Valid @RequestBody SubscriptionPlanDTO planDTO) {
        log.info("Creating new subscription plan: {}", planDTO.getPlanCode());
        SubscriptionPlanDTO createdPlan = planService.createPlan(planDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdPlan);
    }

    /**
     * Update subscription plan (Admin only)
     * PUT /api/subscription-plans/admin/{id}
     */
    @PutMapping("/admin/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SubscriptionPlanDTO> updatePlan(
            @PathVariable Long id,
            @Valid @RequestBody SubscriptionPlanDTO planDTO) {
        log.info("Updating subscription plan: {}", id);
        SubscriptionPlanDTO updatedPlan = planService.updatePlan(id, planDTO);
        return ResponseEntity.ok(updatedPlan);
    }

    /**
     * Delete/deactivate subscription plan (Admin only)
     * DELETE /api/subscription-plans/admin/{id}
     */
    @DeleteMapping("/admin/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<com.nik.payload.response.ApiResponse> deletePlan(@PathVariable Long id) {
        log.info("Deleting subscription plan: {}", id);
        planService.deletePlan(id);
        return ResponseEntity.ok(new com.nik.payload.response.ApiResponse("Subscription plan deactivated successfully", true));
    }

    /**
     * Get all subscription plans including inactive (Admin only)
     * GET /api/subscription-plans/admin/all?page=0&size=20
     */
    @GetMapping("/admin/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<SubscriptionPlanDTO>> getAllPlans(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<SubscriptionPlanDTO> plans = planService.getAllPlans(pageable);
        return ResponseEntity.ok(plans);
    }

    /**
     * Check if plan code exists (Admin only)
     * GET /api/subscription-plans/admin/check-code?code=MONTHLY
     */
    @GetMapping("/admin/check-code")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> checkPlanCode(@RequestParam String code) {
        boolean exists = planService.planCodeExists(code);
        return ResponseEntity.ok(new ApiResponse(
            exists ? "Plan code already exists" : "Plan code is available",
            !exists
        ));
    }
}

