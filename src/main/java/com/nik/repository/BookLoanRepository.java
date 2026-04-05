package com.nik.repository;

import com.nik.domain.BookLoanStatus;
import com.nik.model.BookLoan;
import com.nik.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for BookLoan entity.
 * Provides CRUD operations and custom query methods for checkout/check-in operations.
 */
@Repository
public interface BookLoanRepository extends JpaRepository<BookLoan, Long>, JpaSpecificationExecutor<BookLoan> {

    /**
     * Find all book loans for a specific user
     */
    Page<BookLoan> findByUserId(Long userId, Pageable pageable);

    /**
     * Find all book loans for a specific book
     */
    Page<BookLoan> findByBookId(Long bookId, Pageable pageable);

    /**
     * Find active book loan for a user and book combination
     */
    @Query("SELECT bl FROM BookLoan bl WHERE bl.user.id = :userId AND bl.book.id = :bookId " +
           "AND (bl.status = 'CHECKED_OUT' OR bl.status = 'OVERDUE')")
    Optional<BookLoan> findActiveBookLoanByUserAndBook(
        @Param("userId") Long userId,
        @Param("bookId") Long bookId
    );

    Boolean existsByUserIdAndBookIdAndStatus(Long userId,
                                               Long bookId,
                                               BookLoanStatus activeStatuses);

    boolean existsByUserIdAndBookIdAndStatusIn(Long userId, Long bookId, Collection<BookLoanStatus> statuses);

    Page<BookLoan> findByStatus(BookLoanStatus status, Pageable pageable);

    /**
     * Find book loans by status
     */
    Page<BookLoan> findByStatusAndUser(BookLoanStatus status, User user, Pageable pageable);

    /**
     * Find user loans by a set of statuses (used for condition-fine synchronization).
     */
    List<BookLoan> findByUserIdAndStatusIn(Long userId, Collection<BookLoanStatus> statuses);

    /**
     * Find overdue book loans (past due date and not returned)
     */
    @Query("SELECT bl FROM BookLoan bl WHERE bl.dueDate < :currentDate " +
           "AND (bl.status = 'CHECKED_OUT' OR bl.status = 'OVERDUE')")
    Page<BookLoan> findOverdueBookLoans(@Param("currentDate") LocalDate currentDate, Pageable pageable);

    /**
     * Find book loans due today
     */
    @Query("SELECT bl FROM BookLoan bl WHERE bl.dueDate = :date " +
           "AND bl.status = 'CHECKED_OUT'")
    List<BookLoan> findBookLoansDueOnDate(@Param("date") LocalDate date);

    /**
     * Find book loans by due date and status (for reminders)
     */
    @Query(""" 
SELECT bl FROM BookLoan bl WHERE bl.dueDate = :dueDate AND bl.status = :status 
""")
    Page<BookLoan> findBookLoansByDueDateAndStatus(
        @Param("dueDate") LocalDate dueDate,
        @Param("status") BookLoanStatus status,
        Pageable pageable
    );




    /**
     * Count active book loans for a user
     */
    @Query("SELECT COUNT(bl) FROM BookLoan bl WHERE bl.user.id = :userId " +
           "AND (bl.status = 'CHECKED_OUT' OR bl.status = 'OVERDUE')")
    long countActiveBookLoansByUser(@Param("userId") Long userId);

    /**
     * Count overdue book loans for a user
     */
    @Query("SELECT COUNT(bl) FROM BookLoan bl WHERE bl.user.id = :userId " +
           "AND bl.dueDate < :currentDate " +
           "AND bl.returnDate IS NULL " +
           "AND (bl.status = 'CHECKED_OUT' OR bl.status = 'OVERDUE')")
    long countOverdueBookLoansByUser(@Param("userId") Long userId, @Param("currentDate") LocalDate currentDate);

    /**
     * Find overdue book loans for a specific user (past due date and not returned)
     */
    @Query("SELECT bl FROM BookLoan bl WHERE bl.user.id = :userId " +
           "AND bl.dueDate < :currentDate " +
           "AND bl.returnDate IS NULL " +
           "AND (bl.status = 'CHECKED_OUT' OR bl.status = 'OVERDUE')")
    Page<BookLoan> findOverdueBookLoansByUser(
        @Param("userId") Long userId,
        @Param("currentDate") LocalDate currentDate,
        Pageable pageable
    );

    /**
     * Check if user has any active checkout for a specific book
     */
    @Query("SELECT CASE WHEN COUNT(bl) > 0 THEN true ELSE false END FROM BookLoan bl " +
           "WHERE bl.user.id = :userId AND bl.book.id = :bookId " +
           "AND (bl.status = 'CHECKED_OUT' OR bl.status = 'OVERDUE')")
    boolean hasActiveCheckout(@Param("userId") Long userId, @Param("bookId") Long bookId);



    /**
     * Find book loans by date range
     */
    @Query("SELECT bl FROM BookLoan bl WHERE bl.checkoutDate BETWEEN :startDate AND :endDate")
    Page<BookLoan> findBookLoansByDateRange(
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        Pageable pageable
    );

    /**
     * Count total checkouts in date range
     */
    @Query("SELECT COUNT(bl) FROM BookLoan bl WHERE bl.checkoutDate BETWEEN :startDate AND :endDate")
    long countCheckoutsByDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /**
     * Get most borrowed books
     */
    @Query("SELECT bl.book.id, bl.book.title, COUNT(bl) as count FROM BookLoan bl " +
           "GROUP BY bl.book.id, bl.book.title ORDER BY count DESC")
    List<Object[]> getMostBorrowedBooks(Pageable pageable);

    /**
     * Find book loans by status (non-paginated)
     */
    List<BookLoan> findByStatus(BookLoanStatus status);

    /**
     * Find book loans by status and due date between
     */
    List<BookLoan> findByStatusAndDueDateBetween(BookLoanStatus status, LocalDate startDate, LocalDate endDate);

    /**
     * Count active book loans system-wide.
     */
    @Query("SELECT COUNT(bl) FROM BookLoan bl WHERE bl.status IN ('CHECKED_OUT', 'OVERDUE')")
    long countActiveBookLoans();

    /**
     * Count overdue book loans system-wide.
     */
    @Query("SELECT COUNT(bl) FROM BookLoan bl WHERE bl.dueDate < :currentDate " +
           "AND bl.status IN ('CHECKED_OUT', 'OVERDUE')")
    long countOverdueBookLoans(@Param("currentDate") LocalDate currentDate);

    /**
     * Count returned book loans system-wide.
     */
    long countByStatus(BookLoanStatus status);

    /**
     * Count book loans for a user by status.
     */
    long countByStatusAndUser(BookLoanStatus status, User user);

    long countByUserIdAndStatus(Long userId, BookLoanStatus status);

    long countByUserIdAndStatusIn(Long userId, Collection<BookLoanStatus> statuses);
}

