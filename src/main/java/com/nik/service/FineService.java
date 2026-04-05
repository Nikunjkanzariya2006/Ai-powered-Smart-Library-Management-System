package com.nik.service;

import com.nik.domain.FineStatus;
import com.nik.domain.FineType;
import com.nik.exception.FineException;
import com.nik.exception.BookLoanException;
import com.nik.exception.PaymentException;
import com.nik.payload.dto.FineDTO;
import com.nik.payload.request.CreateFineRequest;
import com.nik.payload.response.PageResponse;
import com.nik.payload.response.PaymentInitiateResponse;

/**
 * Service interface for fine management operations.
 * Handles creation, payment, and querying of fines.
 */
public interface FineService {

    /**
     * Create a new fine manually (admin only)
     * @param createRequest Fine creation details
     * @return Created fine DTO
     * @throws BookLoanException if book loan not found
     */
    FineDTO createFine(CreateFineRequest createRequest) throws BookLoanException;



    /**
     * Pay a fine completely
     * @param fineId Fine ID
     * @param transactionId Optional transaction ID
     * @return Updated fine DTO
     * @throws FineException if fine not found
     */
    PaymentInitiateResponse payFineFully(Long fineId, String transactionId) throws FineException, PaymentException;


    void markFineAsPaid(Long fineId, Long amount, String transactionId);

    /**
     * Get fine by ID
     * @param fineId Fine ID
     * @return Fine DTO
     * @throws FineException if fine not found
     */
    FineDTO getFineById(Long fineId) throws FineException;

    /**
     * Get all fines for a book loan
     * @param bookLoanId Book loan ID
     * @return List of fines
     */
    java.util.List<FineDTO> getFinesByBookLoanId(Long bookLoanId);


    /**
     * Get my  fines (current user)
     * @return List of fines
     * @param status Optional status filter
     * @param type Optional type filter
     */
    java.util.List<FineDTO> getMyFines(
            FineStatus status,
            FineType type
    );


    /**
     * Get all fines with pagination and filtering
     * @param status Optional status filter
     * @param type Optional type filter
     * @param userId Optional filter
     * @param page Page number
     * @param size Page size
     * @return Paginated fines
     */
    PageResponse<FineDTO> getAllFines(
            FineStatus status,
            FineType type,
            Long userId,
            int page,
            int size
    );

    /**
     * Get total unpaid fines for current user
     * @return Total unpaid fine amount
     */
    Long getMyTotalUnpaidFines();

    /**
     * Get total unpaid fines for a user
     * @param userId User ID
     * @return Total unpaid fine amount
     */
    Long getTotalUnpaidFinesByUserId(Long userId);

    /**
     * Get total collected fines (all users)
     * @return Total collected fine amount
     */
    Long getTotalCollectedFines();

    /**
     * Get total outstanding fines (all users)
     * @return Total outstanding fine amount
     */
    Long getTotalOutstandingFines();

    /**
     * Check if a user has any unpaid fines
     * @param userId User ID
     * @return true if user has unpaid fines
     */
    boolean hasUnpaidFines(Long userId);

}

