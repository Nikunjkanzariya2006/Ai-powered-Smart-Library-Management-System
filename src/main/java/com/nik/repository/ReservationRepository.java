package com.nik.repository;

import com.nik.domain.ReservationStatus;
import com.nik.model.Reservation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Reservation entity.
 * Provides CRUD operations and custom query methods for book reservations.
 */
@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {



    /**
     * Find pending reservations for a specific book (ordered by reservation date)
     */
    @Query("SELECT r FROM Reservation r WHERE r.book.id = :bookId " +
           "AND r.status = 'PENDING' ORDER BY r.reservedAt ASC")
    List<Reservation> findPendingReservationsByBook(@Param("bookId") Long bookId);

    /**
     * Get next pending reservation for a book (first in queue)
     */
    Optional<Reservation> findTopByBookIdAndStatusOrderByReservedAtAsc(Long bookId, ReservationStatus status);

    List<Reservation> findByBookIdAndStatusOrderByReservedAtAsc(Long bookId, ReservationStatus status, Pageable pageable);

    long countByBookIdAndStatus(Long bookId, ReservationStatus status);

    boolean existsByBookIdAndStatus(Long bookId, ReservationStatus status);

    @Query("SELECT DISTINCT r.book.id FROM Reservation r WHERE r.status = :status")
    List<Long> findDistinctBookIdsByStatus(@Param("status") ReservationStatus status);

    /**
     * Check if user already has an active reservation for a book
     */
    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM Reservation r " +
           "WHERE r.user.id = :userId AND r.book.id = :bookId " +
           "AND (r.status = 'PENDING' OR r.status = 'AVAILABLE')")
    boolean hasActiveReservation(@Param("userId") Long userId, @Param("bookId") Long bookId);

    /**
     * Count active reservations for a user
     */
    @Query("SELECT COUNT(r) FROM Reservation r WHERE r.user.id = :userId " +
           "AND (r.status = 'PENDING' OR r.status = 'AVAILABLE')")
    long countActiveReservationsByUser(@Param("userId") Long userId);

    /**
     * Count pending reservations for a book
     */
    @Query("SELECT COUNT(r) FROM Reservation r WHERE r.book.id = :bookId " +
           "AND r.status = 'PENDING'")
    long countPendingReservationsByBook(@Param("bookId") Long bookId);

    /**
     * Find reservations that have expired (available but past pickup deadline)
     */
    @Query("SELECT r FROM Reservation r WHERE r.status = 'AVAILABLE' " +
           "AND r.availableUntil < :currentDateTime")
    List<Reservation> findExpiredReservations(@Param("currentDateTime") LocalDateTime currentDateTime);


    /**
     * Find active reservation for user and book
     */
    @Query("SELECT r FROM Reservation r WHERE r.user.id = :userId AND r.book.id = :bookId " +
           "AND (r.status = 'PENDING' OR r.status = 'AVAILABLE')")
    Optional<Reservation> findActiveReservationByUserAndBook(
        @Param("userId") Long userId,
        @Param("bookId") Long bookId
    );



    /**
     * Search reservations with dynamic filters
     */
    @Query("SELECT r FROM Reservation r WHERE " +
           "(:userId IS NULL OR r.user.id = :userId) AND " +
           "(:bookId IS NULL OR r.book.id = :bookId) AND " +
           "(:status IS NULL OR r.status = :status) AND " +
           "(:applyStatuses = false OR r.status IN :statuses) AND " +
           "(:activeOnly = false OR (r.status = 'PENDING' OR r.status = 'AVAILABLE')) AND " +
           "(:applyFromDate = false OR COALESCE(r.fulfilledAt, r.cancelledAt, r.availableAt, r.reservedAt, r.createdAt) >= :fromDateTime) AND " +
           "(:applyToDate = false OR COALESCE(r.fulfilledAt, r.cancelledAt, r.availableAt, r.reservedAt, r.createdAt) <= :toDateTime)")
    Page<Reservation> searchReservationsWithFilters(
        @Param("userId") Long userId,
        @Param("bookId") Long bookId,
        @Param("status") ReservationStatus status,
        @Param("applyStatuses") boolean applyStatuses,
        @Param("statuses") List<ReservationStatus> statuses,
        @Param("activeOnly") boolean activeOnly,
        @Param("applyFromDate") boolean applyFromDate,
        @Param("fromDateTime") LocalDateTime fromDateTime,
        @Param("applyToDate") boolean applyToDate,
        @Param("toDateTime") LocalDateTime toDateTime,
        Pageable pageable
    );
}

