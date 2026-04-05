package com.nik.repository;

import com.nik.domain.PaymentStatus;
import com.nik.model.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Payment entity.
 * Provides CRUD operations and custom query methods for payment transactions.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {

    /**
     * Find payment by transaction ID
     */
    Optional<Payment> findByTransactionId(String transactionId);

    Optional<Payment> findByGatewayOrderId(String gatewayOrderId);

    Optional<Payment> findByGatewayPaymentId(String gatewayPaymentId);

    /**
     * Find all payments for a user
     */
    Page<Payment> findByUserIdAndActiveTrue(Long userId, Pageable pageable);

    List<Payment> findByStatusAndActiveTrueAndUpdatedAtBefore(PaymentStatus status, LocalDateTime updatedAt);

    /**
     * Sum successful active payments completed in the given date range.
     */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p " +
           "WHERE p.active = true AND p.status = :status " +
           "AND p.completedAt >= :startDateTime AND p.completedAt < :endDateTime")
    Long sumAmountByStatusAndCompletedAtBetween(
            @Param("status") PaymentStatus status,
            @Param("startDateTime") LocalDateTime startDateTime,
            @Param("endDateTime") LocalDateTime endDateTime
    );
}

