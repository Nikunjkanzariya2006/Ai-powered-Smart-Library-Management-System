package com.nik.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.nik.domain.BookLoanStatus;
import com.nik.domain.BookLoanType;
import com.nik.domain.FineStatus;
import com.nik.domain.FineType;
import com.nik.domain.ReservationStatus;
import com.nik.exception.AuthenticationFailureException;
import com.nik.exception.BookException;
import com.nik.exception.BookLoanException;
import com.nik.exception.SubscriptionException;
import com.nik.exception.UserException;
import com.nik.mapper.BookLoanMapper;
import com.nik.model.Book;
import com.nik.model.BookLoan;
import com.nik.model.Fine;
import com.nik.model.User;
import com.nik.payload.CheckoutStatistics;
import com.nik.payload.dto.BookLoanDTO;
import com.nik.payload.dto.SubscriptionDTO;
import com.nik.payload.request.BookLoanSearchRequest;
import com.nik.payload.request.CheckinRequest;
import com.nik.payload.request.CheckoutRequest;
import com.nik.payload.request.RenewalRequest;
import com.nik.payload.response.PageResponse;
import com.nik.repository.BookLoanRepository;
import com.nik.repository.BookRepository;
import com.nik.repository.FineRepository;
import com.nik.repository.ReservationRepository;
import com.nik.repository.UserRepository;
import com.nik.service.BookLoanService;
import com.nik.service.FineCalculationService;
import com.nik.service.ReservationQueueService;
import com.nik.service.SubscriptionService;

import jakarta.transaction.Transactional;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;

/**
 * Implementation of BookLoanService interface.
 * Handles all business logic for checkout/check-in operations.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class BookLoanServiceImpl implements BookLoanService {
    private static final Logger logger = LoggerFactory.getLogger(BookLoanServiceImpl.class);

    private final BookLoanRepository bookLoanRepository;
    private final BookRepository bookRepository;
    private final UserRepository userRepository;
    private final BookLoanMapper bookLoanMapper;
    private final FineCalculationService fineCalculationService;
    private final SubscriptionService subscriptionService;
    private final FineRepository fineRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationQueueService reservationQueueService;

    // Default renewal/search limits used where subscription-specific settings do not apply.
    private static final int MAX_ACTIVE_CHECKOUTS = 5;
    private static final int DEFAULT_CHECKOUT_DAYS = 14;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "createdAt", "updatedAt", "checkoutDate", "dueDate", "returnDate", "status"
    );

    @Override
    public BookLoanDTO checkoutBook(CheckoutRequest checkoutRequest)
            throws BookLoanException, BookException, UserException {
        User currentUser = getCurrentAuthenticatedUser();
        return checkoutBookForUser(currentUser.getId(), checkoutRequest);
    }

    @Override
    public BookLoanDTO checkoutBookForUser(Long userId,
                                           CheckoutRequest checkoutRequest)
            throws BookLoanException, BookException, UserException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserException("User not found with id: " + userId));
        SubscriptionDTO subscription;
        try {
            subscription = subscriptionService.getUsersActiveSubscription(userId);
        } catch (SubscriptionException e) {
            throw new BookLoanException(
                "No active subscription found. Please subscribe to checkout books. " +
                "Visit /api/subscriptions/subscribe to get started.", e);
        }
        Book book = bookRepository.findById(checkoutRequest.getBookId())
                .orElseThrow(() -> new BookException("Book not found with id: " + checkoutRequest.getBookId()));

        if (!book.getActive()) {
            throw new BookLoanException("Book is not active and cannot be checked out");
        }

        if (book.getAvailableCopies() <= 0) {
            throw new BookLoanException("Book is not available for checkout. No copies available.");
        }

        boolean hasActiveAvailableHolds = reservationRepository.existsByBookIdAndStatus(
                book.getId(),
                ReservationStatus.AVAILABLE
        );
        if (hasActiveAvailableHolds) {
            boolean userHasAvailableHold = reservationRepository
                    .findActiveReservationByUserAndBook(userId, book.getId())
                    .map(reservation -> reservation.getStatus() == ReservationStatus.AVAILABLE)
                    .orElse(false);

            if (!userHasAvailableHold) {
                throw new BookLoanException(
                        "This book is currently reserved for earlier reservation holders. " +
                        "Please wait for your turn in the reservation queue notification."
                );
            }
        }
        if (bookLoanRepository.hasActiveCheckout(userId, book.getId())) {
            throw new BookLoanException("User already has this book checked out");
        }
        long activeCheckouts = bookLoanRepository.countActiveBookLoansByUser(userId);
        int maxBooksAllowed = subscription.getMaxBooksAllowed();

        if (activeCheckouts >= maxBooksAllowed) {
            throw new BookLoanException(
                "You have reached your subscription limit of " + maxBooksAllowed + " active checkouts. " +
                "Your current plan: " + subscription.getPlanName() + ". " +
                "Please return books or upgrade your subscription for more checkouts.");
        }
        long overdueCount = bookLoanRepository.countOverdueBookLoansByUser(userId, LocalDate.now());
        if (overdueCount > 0) {
            throw new BookLoanException(
                    "User has " + overdueCount + " overdue book(s). Cannot checkout until books are returned.");
        }
        ensureConditionFinesForUser(userId);
        if (fineRepository.hasUnpaidFines(userId)) {
            throw new BookLoanException(
                    "You have unpaid fines (overdue/damage/loss). Please pay all pending fines before checking out a new book.");
        }
        BookLoan bookLoan = new BookLoan();
        bookLoan.setUser(user);
        bookLoan.setBook(book);
        bookLoan.setType(BookLoanType.CHECKOUT);
        bookLoan.setStatus(BookLoanStatus.CHECKED_OUT);
        bookLoan.setCheckoutDate(LocalDate.now());
        int checkoutDays = checkoutRequest.getCheckoutDays() != null
                ? Math.min(checkoutRequest.getCheckoutDays(), subscription.getMaxDaysPerBook())
                : subscription.getMaxDaysPerBook();
        bookLoan.setDueDate(LocalDate.now().plusDays(checkoutDays));

        bookLoan.setRenewalCount(0);
        bookLoan.setMaxRenewals(2);

        bookLoan.setNotes(checkoutRequest.getNotes());
        bookLoan.setIsOverdue(false);
        bookLoan.setOverdueDays(0);
        book.setAvailableCopies(book.getAvailableCopies() - 1);
        bookRepository.save(book);
        BookLoan savedBookLoan = bookLoanRepository.save(bookLoan);

        return bookLoanMapper.toDTO(savedBookLoan);
    }

    @Override
    public BookLoanDTO checkinBook(CheckinRequest checkinRequest) throws BookLoanException {
        BookLoan bookLoan = bookLoanRepository.findById(checkinRequest.getBookLoanId())
                .orElseThrow(() -> new BookLoanException(
                        "Book loan not found with id: " + checkinRequest.getBookLoanId()));
        if (bookLoan.getStatus() == BookLoanStatus.RETURNED || bookLoan.getStatus() == BookLoanStatus.LOST) {
            throw new BookLoanException("Book loan is already closed and cannot be checked in again");
        }
        BookLoanStatus condition = checkinRequest.getCondition();
        if (condition == null) {
            condition = BookLoanStatus.RETURNED;
        }
        if (condition != BookLoanStatus.RETURNED
            && condition != BookLoanStatus.LOST
            && condition != BookLoanStatus.DAMAGED) {
            throw new BookLoanException("Invalid return condition. Must be RETURNED, LOST, or DAMAGED");
        }
        // Normal returns are blocked while a pending fine is still open for the same loan.
        if (condition == BookLoanStatus.RETURNED && hasPendingFineForLoan(bookLoan.getId())) {
            throw new BookLoanException("Please pay pending fine before returning this book.");
        }

        bookLoan.setStatus(condition);
        // LOST and DAMAGED represent incident states, so the loan stays open for follow-up handling.
        if (condition == BookLoanStatus.LOST || condition == BookLoanStatus.DAMAGED) {
            bookLoan.setReturnDate(null);
        } else {
            bookLoan.setReturnDate(LocalDate.now());
        }
        BigDecimal fine = BigDecimal.ZERO;
        if (LocalDate.now().isAfter(bookLoan.getDueDate())) {
            fine = fineCalculationService.calculateOverdueFine(bookLoan);
            int overdueDays = fineCalculationService.calculateOverdueDays(
                    bookLoan.getDueDate(), LocalDate.now());
            bookLoan.setOverdueDays(overdueDays);
            upsertOverdueFine(bookLoan, fine);
        }

        if (condition == BookLoanStatus.LOST) {
            BigDecimal lossFine = bookLoan.getBook().getPrice() == null
                    ? BigDecimal.ZERO
                    : bookLoan.getBook().getPrice();
            upsertConditionFine(bookLoan, FineType.LOSS, lossFine, "Lost book fine (100% of book price)");
        } else if (condition == BookLoanStatus.DAMAGED) {
            BigDecimal damageFine = bookLoan.getBook().getPrice() == null
                    ? BigDecimal.ZERO
                    : bookLoan.getBook().getPrice().multiply(new BigDecimal("0.50"));
            upsertConditionFine(bookLoan, FineType.DAMAGE, damageFine, "Damaged book fine (50% of book price)");
        }

        bookLoan.setIsOverdue(false); // No longer overdue once returned
        if (checkinRequest.getNotes() != null) {
            String existingNotes = bookLoan.getNotes() != null ? bookLoan.getNotes() + "\n" : "";
            bookLoan.setNotes(existingNotes + "Return: " + checkinRequest.getNotes());
        }
        if (condition == BookLoanStatus.RETURNED) {
            Book book = bookLoan.getBook();
            int currentAvailable = book.getAvailableCopies() == null ? 0 : book.getAvailableCopies();
            int totalCopies = book.getTotalCopies() == null ? currentAvailable : book.getTotalCopies();
            int nextAvailable = Math.min(totalCopies, currentAvailable + 1);
            book.setAvailableCopies(nextAvailable);
            bookRepository.save(book);
            processNextReservation(book.getId());
        }
        BookLoan savedBookLoan = bookLoanRepository.save(bookLoan);

        return bookLoanMapper.toDTO(savedBookLoan);
    }

    @Override
    public BookLoanDTO renewCheckout(RenewalRequest renewalRequest) throws BookLoanException {
        BookLoan bookLoan = bookLoanRepository.findById(renewalRequest.getBookLoanId())
                .orElseThrow(() -> new BookLoanException(
                        "Book loan not found with id: " + renewalRequest.getBookLoanId()));
        boolean isRenewableStatus = bookLoan.getStatus() == BookLoanStatus.CHECKED_OUT
                || bookLoan.getStatus() == BookLoanStatus.OVERDUE
                || bookLoan.getStatus() == BookLoanStatus.DAMAGED;
        if (!isRenewableStatus || bookLoan.getReturnDate() != null) {
            throw new BookLoanException("Book cannot be renewed in current state");
        }
        if (bookLoan.getRenewalCount() >= bookLoan.getMaxRenewals()) {
            throw new BookLoanException(
                    "Maximum renewal limit reached (" + bookLoan.getMaxRenewals() + ")");
        }
        if (hasPendingFineForLoan(bookLoan.getId())) {
            throw new BookLoanException("Please pay overdue fine before renewing this book.");
        }
        int extensionDays = renewalRequest.getExtensionDays() != null
                ? renewalRequest.getExtensionDays()
                : DEFAULT_CHECKOUT_DAYS;
        LocalDate renewalBaseDate = LocalDate.now().isAfter(bookLoan.getDueDate())
                ? LocalDate.now()
                : bookLoan.getDueDate();
        bookLoan.setDueDate(renewalBaseDate.plusDays(extensionDays));
        bookLoan.setStatus(BookLoanStatus.CHECKED_OUT);
        bookLoan.setIsOverdue(false);
        bookLoan.setOverdueDays(0);
        bookLoan.setRenewalCount(bookLoan.getRenewalCount() + 1);
        if (renewalRequest.getNotes() != null) {
            String existingNotes = bookLoan.getNotes() != null ? bookLoan.getNotes() + "\n" : "";
            bookLoan.setNotes(existingNotes + "Renewal: " + renewalRequest.getNotes());
        }
        BookLoan savedBookLoan = bookLoanRepository.save(bookLoan);

        return bookLoanMapper.toDTO(savedBookLoan);
    }

    @Override
    public BookLoanDTO getBookLoanById(Long bookLoanId) throws BookLoanException {
        BookLoan bookLoan = bookLoanRepository.findById(bookLoanId)
                .orElseThrow(() -> new BookLoanException("Book loan not found with id: " + bookLoanId));
        return bookLoanMapper.toDTO(bookLoan);
    }

    @Override
    public PageResponse<BookLoanDTO> getMyBookLoans(BookLoanStatus status, int page, int size) {
        User currentUser = getCurrentAuthenticatedUser();
        return getUserBookLoans(currentUser.getId(), status, page, size);
    }

    @Override
    public PageResponse<BookLoanDTO> getUserBookLoans(Long userId,
                                                      BookLoanStatus status,
                                                      int page, int size) {
        synchronizeLoanStateForUser(userId);
        Page<BookLoan> bookLoanPage;

        if (status!=null) {
            // Return only active checkouts, sorted by due date
            Pageable pageable = PageRequest.of(page, size, Sort.by("dueDate").ascending());
            if (status == BookLoanStatus.OVERDUE) {
                bookLoanPage = bookLoanRepository.findOverdueBookLoansByUser(
                        userId, LocalDate.now(), pageable);
            } else {
                User requestedUser = userRepository.findById(userId)
                        .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("User not found with id: " + userId));
                bookLoanPage = bookLoanRepository.findByStatusAndUser(
                        status, requestedUser, pageable);
            }
        } else {
            // Return all history (both active and returned), sorted by creation date descending
            Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
            bookLoanPage = bookLoanRepository.findByUserId(userId, pageable);
        }

        // Keep legacy condition-fine rows synchronized whenever loans are fetched.
        for (BookLoan loan : bookLoanPage.getContent()) {
            ensureConditionFineForLoan(loan);
        }

        return convertToPageResponse(bookLoanPage);
    }






    @Override
    public PageResponse<BookLoanDTO> getBookLoans(BookLoanSearchRequest searchRequest) {
        if (searchRequest == null) {
            searchRequest = new BookLoanSearchRequest();
        }
        Pageable pageable = createPageable(
                searchRequest.getPage() == null ? 0 : searchRequest.getPage(),
                searchRequest.getSize() == null ? 20 : searchRequest.getSize(),
                searchRequest.getSortBy(),
                searchRequest.getSortDirection()
        );

        Specification<BookLoan> specification = buildLoanSearchSpecification(searchRequest);
        Page<BookLoan> bookLoanPage = bookLoanRepository.findAll(specification, pageable);
        return convertToPageResponse(bookLoanPage);
    }

    @Override
    public BookLoanDTO updateBookLoan(Long bookLoanId, com.nik.payload.request.UpdateBookLoanRequest updateRequest) throws BookLoanException {
        BookLoan bookLoan = bookLoanRepository.findById(bookLoanId)
                .orElseThrow(() -> new BookLoanException("Book loan not found with id: " + bookLoanId));
        if (updateRequest.getStatus() != null) {
            validateStatusTransition(bookLoan.getStatus(), updateRequest.getStatus());
            bookLoan.setStatus(updateRequest.getStatus());
        }

        if (updateRequest.getDueDate() != null) {
            bookLoan.setDueDate(updateRequest.getDueDate());
        }

        if (updateRequest.getReturnDate() != null) {
            bookLoan.setReturnDate(updateRequest.getReturnDate());
        }

        if (updateRequest.getMaxRenewals() != null) {
            bookLoan.setMaxRenewals(updateRequest.getMaxRenewals());
        }



        if (updateRequest.getNotes() != null) {
            String existingNotes = bookLoan.getNotes() != null ? bookLoan.getNotes() + "\n" : "";
            bookLoan.setNotes(existingNotes + "Admin update: " + updateRequest.getNotes());
        }
        BookLoan savedBookLoan = bookLoanRepository.save(bookLoan);
        return bookLoanMapper.toDTO(savedBookLoan);
    }

    @Override
    public int updateOverdueBookLoans() {
        Pageable pageable = PageRequest.of(0, 1000);
        Page<BookLoan> overduePage = bookLoanRepository.findOverdueBookLoans(LocalDate.now(), pageable);

        int updateCount = 0;
        for (BookLoan bookLoan : overduePage.getContent()) {
            if (syncOverdueStateAndFine(bookLoan)) {
                updateCount++;
            }
        }

        return updateCount;
    }

    @Override
    public int synchronizeLoanStateForUser(Long userId) {
        List<BookLoan> activeLoans = bookLoanRepository.findByUserIdAndStatusIn(
                userId,
                List.of(BookLoanStatus.CHECKED_OUT, BookLoanStatus.OVERDUE)
        );

        int updatedCount = 0;
        for (BookLoan loan : activeLoans) {
            if (syncOverdueStateAndFine(loan)) {
                updatedCount++;
            }
        }

        return updatedCount;
    }

    @Override
    public CheckoutStatistics getCheckoutStatistics() {
        long totalCheckouts = bookLoanRepository.count();
        long activeCheckouts = bookLoanRepository.countActiveBookLoans();
        long overdueCheckouts = bookLoanRepository.countOverdueBookLoans(LocalDate.now());
        long totalReturns = bookLoanRepository.countByStatus(BookLoanStatus.RETURNED);

        return new CheckoutStatistics(
                totalCheckouts,
                activeCheckouts,
                overdueCheckouts,
                totalReturns,
               null,
                0
        );
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

    private Pageable createPageable(int page, int size, String sortBy, String sortDirection) {
        page = Math.max(page, 0);
        size = Math.min(size, 100);
        size = Math.max(size, 1);

        String safeSortBy = ALLOWED_SORT_FIELDS.contains(sortBy) ? sortBy : "createdAt";
        String safeSortDirection = sortDirection == null ? "DESC" : sortDirection;
        Sort sort = safeSortDirection.equalsIgnoreCase("ASC")
                ? Sort.by(safeSortBy).ascending()
                : Sort.by(safeSortBy).descending();

        return PageRequest.of(page, size, sort);
    }

    private Specification<BookLoan> buildLoanSearchSpecification(BookLoanSearchRequest searchRequest) {
        return (root, query, cb) -> {
            query.distinct(true);
            List<Predicate> predicates = new ArrayList<>();

            if (searchRequest.getUserId() != null) {
                predicates.add(cb.equal(root.get("user").get("id"), searchRequest.getUserId()));
            }

            if (searchRequest.getBookId() != null) {
                predicates.add(cb.equal(root.get("book").get("id"), searchRequest.getBookId()));
            }

            if (searchRequest.getStatus() != null) {
                predicates.add(cb.equal(root.get("status"), searchRequest.getStatus()));
            }

            if (searchRequest.getStartDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("checkoutDate"), searchRequest.getStartDate()));
            }

            if (searchRequest.getEndDate() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("checkoutDate"), searchRequest.getEndDate()));
            }

            if (Boolean.TRUE.equals(searchRequest.getOverdueOnly())) {
                predicates.add(
                        cb.or(
                                cb.equal(root.get("status"), BookLoanStatus.OVERDUE),
                                cb.and(
                                        cb.lessThan(root.get("dueDate"), LocalDate.now()),
                                        root.get("status").in(BookLoanStatus.CHECKED_OUT, BookLoanStatus.OVERDUE)
                                )
                        )
                );
            }

            if (Boolean.TRUE.equals(searchRequest.getUnpaidFinesOnly())) {
                var fineJoin = root.join("fines", JoinType.LEFT);
                predicates.add(cb.equal(fineJoin.get("status"), FineStatus.PENDING));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private PageResponse<BookLoanDTO> convertToPageResponse(Page<BookLoan> bookLoanPage) {
        List<BookLoanDTO> bookLoanDTOs = bookLoanPage.getContent()
                .stream()
                .map(bookLoanMapper::toDTO)
                .collect(Collectors.toList());

        return new PageResponse<>(
                bookLoanDTOs,
                bookLoanPage.getNumber(),
                bookLoanPage.getSize(),
                bookLoanPage.getTotalElements(),
                bookLoanPage.getTotalPages(),
                bookLoanPage.isLast(),
                bookLoanPage.isFirst(),
                bookLoanPage.isEmpty()
        );
    }

    /**
     * Process next reservation when book becomes available
     */
    private void processNextReservation(Long bookId) {
        try {
            reservationQueueService.processNextReservation(bookId);
        } catch (Exception e) {
            logger.warn("Failed to process reservation for book {}", bookId, e);
        }
    }

    private boolean hasPendingFineForLoan(Long bookLoanId) {
        return fineRepository.findByBookLoanId(bookLoanId).stream()
                .anyMatch(fine -> fine.isPending() && fine.getAmountOutstanding() > 0);
    }

    private boolean syncOverdueStateAndFine(BookLoan bookLoan) {
        if (bookLoan.getReturnDate() != null) {
            return clearOverdueFlags(bookLoan);
        }

        boolean isOverdueNow = LocalDate.now().isAfter(bookLoan.getDueDate())
                && (bookLoan.getStatus() == BookLoanStatus.CHECKED_OUT
                || bookLoan.getStatus() == BookLoanStatus.OVERDUE);

        if (!isOverdueNow) {
            return clearOverdueFlags(bookLoan);
        }

        boolean changed = false;
        if (bookLoan.getStatus() != BookLoanStatus.OVERDUE) {
            bookLoan.setStatus(BookLoanStatus.OVERDUE);
            changed = true;
        }
        if (!Boolean.TRUE.equals(bookLoan.getIsOverdue())) {
            bookLoan.setIsOverdue(true);
            changed = true;
        }

        int overdueDays = fineCalculationService.calculateOverdueDays(bookLoan.getDueDate(), LocalDate.now());
        if (bookLoan.getOverdueDays() == null || !bookLoan.getOverdueDays().equals(overdueDays)) {
            bookLoan.setOverdueDays(overdueDays);
            changed = true;
        }

        BigDecimal fine = fineCalculationService.calculateOverdueFine(bookLoan);
        upsertOverdueFine(bookLoan, fine);

        if (changed) {
            bookLoanRepository.save(bookLoan);
        }
        return changed;
    }

    private boolean clearOverdueFlags(BookLoan bookLoan) {
        boolean changed = false;

        if (bookLoan.getStatus() == BookLoanStatus.OVERDUE && bookLoan.getReturnDate() == null) {
            bookLoan.setStatus(BookLoanStatus.CHECKED_OUT);
            changed = true;
        }
        if (Boolean.TRUE.equals(bookLoan.getIsOverdue())) {
            bookLoan.setIsOverdue(false);
            changed = true;
        }
        if ((bookLoan.getOverdueDays() != null && bookLoan.getOverdueDays() != 0)) {
            bookLoan.setOverdueDays(0);
            changed = true;
        }

        if (changed) {
            bookLoanRepository.save(bookLoan);
        }

        return changed;
    }

    private void upsertOverdueFine(BookLoan bookLoan, BigDecimal fineAmount) {
        long normalizedAmount = normalizeFineAmount(fineAmount);
        if (normalizedAmount <= 0L) {
            return;
        }

        List<Fine> existingOverdueFines = fineRepository.findByBookLoanIdAndType(bookLoan.getId(), FineType.OVERDUE);
        if (existingOverdueFines.isEmpty()) {
            Fine newFine = Fine.builder()
                    .bookLoan(bookLoan)
                    .user(bookLoan.getUser())
                    .type(FineType.OVERDUE)
                    .amount(normalizedAmount)
                    .amountPaid(0L)
                    .status(FineStatus.PENDING)
                    .reason("Overdue fine: ₹1 per day")
                    .notes("Auto-generated for overdue loan")
                    .build();
            fineRepository.save(newFine);
            return;
        }

        boolean hasSettledOverdueFine = existingOverdueFines.stream()
                .anyMatch(fine -> fine.getStatus() == FineStatus.PAID);
        if (hasSettledOverdueFine) {
            return;
        }

        Fine targetFine = existingOverdueFines.stream()
                .filter(Fine::isPending)
                .findFirst()
                .orElse(null);

        if (targetFine == null) {
            // Fine already settled; don't recreate automatically.
            return;
        }

        targetFine.setAmount(Math.max(normalizedAmount, targetFine.getAmountPaid()));
        if (targetFine.getAmountPaid() >= targetFine.getAmount()) {
            targetFine.setStatus(FineStatus.PAID);
        } else {
            targetFine.setStatus(FineStatus.PENDING);
        }

        fineRepository.save(targetFine);
    }

    private void upsertConditionFine(BookLoan bookLoan, FineType fineType, BigDecimal fineAmount, String reason) {
        long normalizedAmount = normalizeFineAmount(fineAmount);
        if (normalizedAmount <= 0L) {
            return;
        }

        List<Fine> existingFines = fineRepository.findByBookLoanIdAndType(bookLoan.getId(), fineType);

        Fine fine = existingFines.stream()
                .filter(Fine::isPending)
                .findFirst()
                .orElse(null);

        if (fine == null) {
            fine = Fine.builder()
                    .bookLoan(bookLoan)
                    .user(bookLoan.getUser())
                    .type(fineType)
                    .amount(normalizedAmount)
                    .amountPaid(0L)
                    .status(FineStatus.PENDING)
                    .reason(reason)
                    .notes("Auto-generated on book return condition")
                    .build();
        } else {
            fine.setAmount(Math.max(normalizedAmount, fine.getAmountPaid()));
            if (fine.getAmountPaid() >= fine.getAmount()) {
                fine.setStatus(FineStatus.PAID);
            } else {
                fine.setStatus(FineStatus.PENDING);
            }
            fine.setReason(reason);
        }

        fineRepository.save(fine);
    }

    private void ensureConditionFinesForUser(Long userId) {
        List<BookLoan> conditionLoans = bookLoanRepository.findByUserIdAndStatusIn(
                userId,
                List.of(BookLoanStatus.DAMAGED, BookLoanStatus.LOST)
        );

        for (BookLoan loan : conditionLoans) {
            ensureConditionFineForLoan(loan);
        }
    }

    private void ensureConditionFineForLoan(BookLoan bookLoan) {
        if (bookLoan == null || bookLoan.getStatus() == null || bookLoan.getBook() == null) {
            return;
        }

        // Normalize legacy rows: LOST/DAMAGED are incident states (book not finally returned yet).
        if ((bookLoan.getStatus() == BookLoanStatus.LOST || bookLoan.getStatus() == BookLoanStatus.DAMAGED)
                && bookLoan.getReturnDate() != null) {
            bookLoan.setReturnDate(null);
            bookLoanRepository.save(bookLoan);
        }

        BigDecimal bookPrice = bookLoan.getBook().getPrice();
        if (bookPrice == null || bookPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        if (bookLoan.getStatus() == BookLoanStatus.LOST) {
            ensureConditionFineExistsForLegacyLoan(
                    bookLoan,
                    FineType.LOSS,
                    bookPrice,
                    "Lost book fine (100% of book price)"
            );
            return;
        }

        if (bookLoan.getStatus() == BookLoanStatus.DAMAGED) {
            ensureConditionFineExistsForLegacyLoan(
                    bookLoan,
                    FineType.DAMAGE,
                    bookPrice.multiply(new BigDecimal("0.50")),
                    "Damaged book fine (50% of book price)"
            );
        }
    }

    private void ensureConditionFineExistsForLegacyLoan(
            BookLoan bookLoan,
            FineType fineType,
            BigDecimal fineAmount,
            String reason
    ) {
        List<Fine> existing = fineRepository.findByBookLoanIdAndType(bookLoan.getId(), fineType);
        if (!existing.isEmpty()) {
            return;
        }
        upsertConditionFine(bookLoan, fineType, fineAmount, reason);
    }

    private long normalizeFineAmount(BigDecimal amount) {
        if (amount == null) {
            return 0L;
        }
        return amount.max(BigDecimal.ZERO)
                .setScale(0, RoundingMode.CEILING)
                .longValue();
    }

    private void validateStatusTransition(BookLoanStatus currentStatus, BookLoanStatus nextStatus) throws BookLoanException {
        if (currentStatus == null || nextStatus == null || currentStatus == nextStatus) {
            return;
        }

        // Lost item cannot be marked as returned/checked-out again.
        if (currentStatus == BookLoanStatus.LOST
                && (nextStatus == BookLoanStatus.RETURNED
                || nextStatus == BookLoanStatus.CHECKED_OUT
                || nextStatus == BookLoanStatus.OVERDUE)) {
            throw new BookLoanException("Invalid status transition: LOST book cannot be returned or reactivated.");
        }

        // Returned is terminal for an existing loan record.
        if (currentStatus == BookLoanStatus.RETURNED
                && (nextStatus == BookLoanStatus.CHECKED_OUT || nextStatus == BookLoanStatus.OVERDUE)) {
            throw new BookLoanException("Invalid status transition for closed loan record.");
        }
    }
}




