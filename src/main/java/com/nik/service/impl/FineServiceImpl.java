package com.nik.service.impl;

import com.nik.domain.FineStatus;
import com.nik.domain.FineType;
import com.nik.domain.PaymentGateway;
import com.nik.domain.PaymentType;
import com.nik.exception.AuthenticationFailureException;
import com.nik.exception.FineException;
import com.nik.exception.BookLoanException;
import com.nik.exception.PaymentException;
import com.nik.mapper.FineMapper;
import com.nik.model.BookLoan;
import com.nik.model.Fine;
import com.nik.model.User;
import com.nik.payload.dto.FineDTO;
import com.nik.payload.request.CreateFineRequest;
import com.nik.payload.request.PaymentInitiateRequest;
import com.nik.payload.response.PageResponse;
import com.nik.payload.response.PaymentInitiateResponse;
import com.nik.repository.BookLoanRepository;
import com.nik.repository.FineRepository;
import com.nik.repository.UserRepository;
import com.nik.service.FineService;
import com.nik.service.PaymentService;
import com.nik.service.BookLoanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of FineService interface.
 * Handles all business logic for fine operations.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class FineServiceImpl implements FineService {

    private final FineRepository fineRepository;
    private final BookLoanRepository bookLoanRepository;
    private final UserRepository userRepository;
    private final FineMapper fineMapper;
    private final PaymentService paymentService;
    private final BookLoanService bookLoanService;

    @Override
    public FineDTO createFine(CreateFineRequest createRequest) throws BookLoanException {
        BookLoan bookLoan = bookLoanRepository.findById(createRequest.getBookLoanId())
                .orElseThrow(() -> new BookLoanException(
                        "Book loan not found with id: " + createRequest.getBookLoanId()));

        Fine fine = Fine.builder()
                
                .bookLoan(bookLoan)
                .user(bookLoan.getUser())
                .type(createRequest.getType())
                .amount(createRequest.getAmount())
                .amountPaid(0L)
                .status(FineStatus.PENDING)
                .reason(createRequest.getReason())
                .notes(createRequest.getNotes()).build();

        Fine savedFine = fineRepository.save(fine);
        log.info("Created fine: {} for book loan: {}", savedFine.getId(), bookLoan.getId());
        return fineMapper.toDTO(savedFine);
    }

    @Override
    public PaymentInitiateResponse payFineFully(Long fineId, String transactionId) throws FineException, PaymentException {
        Fine fine = fineRepository.findById(fineId)
                .orElseThrow(() -> new FineException("Fine not found with id: " + fineId));

        if (fine.getStatus() == FineStatus.PAID) {
            throw new FineException("Fine is already fully paid");
        }

        User currentUser = getCurrentAuthenticatedUser();

        PaymentInitiateRequest context = PaymentInitiateRequest.builder()
                .userId(currentUser.getId())
                .fineId(fine.getId())
                .paymentType(PaymentType.FINE)
                .gateway(PaymentGateway.RAZORPAY)
                .amount(fine.getAmountOutstanding())
                .currency("INR")
                .description("Library fine payment for fine ID " + fine.getId())
                .build();

        // ✅ Delegate everything to PaymentService

        return paymentService.initiatePayment(context);
    }

    @Override
    @Transactional
    public void markFineAsPaid(Long fineId, Long amount, String transactionId) throws FineException {
        Fine fine = fineRepository.findById(fineId)
                .orElseThrow(() -> new FineException(
                        "Fine not found with id: " + fineId));

        // Apply payment amount safely
        fine.applyPayment(amount);
        fine.setTransactionId(transactionId);
        fine.setStatus(FineStatus.PAID);
        fine.setUpdatedAt(LocalDateTime.now());

        fineRepository.save(fine);

        log.info("Fine {} marked as fully paid (txn: {})", fineId, transactionId);
    }

    @Override
    public FineDTO getFineById(Long fineId) throws FineException {
        Fine fine = fineRepository.findById(fineId)
                .orElseThrow(() -> new FineException("Fine not found with id: " + fineId));
        return fineMapper.toDTO(fine);
    }

    @Override
    public List<FineDTO> getFinesByBookLoanId(Long bookLoanId) {
        List<Fine> fines = fineRepository.findByBookLoanId(bookLoanId);
        return fineMapper.toDTOList(fines);
    }

    @Override
    public List<FineDTO> getMyFines(FineStatus status, FineType type) {
        User currentUser = getCurrentAuthenticatedUser();
        bookLoanService.synchronizeLoanStateForUser(currentUser.getId());
        List<Fine> fines = fineRepository.findByUserIdAndOptionalFilters(currentUser.getId(), status, type);
        return fineMapper.toDTOList(fines);
    }

    @Override
    public PageResponse<FineDTO> getAllFines(FineStatus status,
                                             FineType type,
                                             Long userId,
                                             int page,
                                             int size
    ) {
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by("createdAt").descending());

        Page<Fine> finePage = fineRepository.findAllWithFilters(
                userId,
                status,
                type,
                pageable
        );
        return convertToPageResponse(finePage);
    }

    @Override
    public Long getMyTotalUnpaidFines() {
        User currentUser = getCurrentAuthenticatedUser();
        bookLoanService.synchronizeLoanStateForUser(currentUser.getId());
        return getTotalUnpaidFinesByUserId(currentUser.getId());
    }

    @Override
    public Long getTotalUnpaidFinesByUserId(Long userId) {
        return fineRepository.getTotalUnpaidFinesByUserId(userId);
    }

    @Override
    public Long getTotalCollectedFines() {
        return fineRepository.getTotalCollectedFines();
    }

    @Override
    public Long getTotalOutstandingFines() {
        return fineRepository.getTotalOutstandingFines();
    }

    @Override
    public boolean hasUnpaidFines(Long userId) {
        return fineRepository.hasUnpaidFines(userId);
    }

    private User getCurrentAuthenticatedUser() {
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

    private PageResponse<FineDTO> convertToPageResponse(Page<Fine> finePage) {
        List<FineDTO> fineDTOs = finePage.getContent()
                .stream()
                .map(fineMapper::toDTO)
                .collect(Collectors.toList());

        return new PageResponse<>(
                fineDTOs,
                finePage.getNumber(),
                finePage.getSize(),
                finePage.getTotalElements(),
                finePage.getTotalPages(),
                finePage.isLast(),
                finePage.isFirst(),
                finePage.isEmpty()
        );
    }
}


