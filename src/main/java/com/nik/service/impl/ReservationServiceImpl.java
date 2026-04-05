package com.nik.service.impl;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nik.domain.BookLoanStatus;
import com.nik.domain.NotificationType;
import com.nik.domain.ReservationStatus;
import com.nik.domain.UserRole;
import com.nik.exception.AuthenticationFailureException;
import com.nik.exception.BookException;
import com.nik.exception.ReservationException;
import com.nik.exception.UserException;
import com.nik.mapper.ReservationMapper;
import com.nik.model.Book;
import com.nik.model.Reservation;
import com.nik.model.User;
import com.nik.payload.dto.ReservationDTO;
import com.nik.payload.request.CheckoutRequest;
import com.nik.payload.request.ReservationRequest;
import com.nik.payload.request.ReservationSearchRequest;
import com.nik.payload.response.PageResponse;
import com.nik.repository.BookLoanRepository;
import com.nik.repository.BookRepository;
import com.nik.repository.FineRepository;
import com.nik.repository.ReservationRepository;
import com.nik.repository.UserRepository;
import com.nik.service.BookLoanService;
import com.nik.service.NotificationService;
import com.nik.service.ReservationQueueService;
import com.nik.service.ReservationService;
import com.nik.service.SubscriptionService;

import lombok.RequiredArgsConstructor;

/**
 * Implementation of ReservationService for managing book reservations
 */
@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private static final Logger logger = LoggerFactory.getLogger(ReservationServiceImpl.class);
    private static final int MAX_ACTIVE_RESERVATIONS = 3; // Max active reservations per user
    private static final int HOLD_PERIOD_HOURS = 24; // Hold for 24 hours after availability notification
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "reservedAt", "availableAt", "queuePosition", "status", "createdAt", "updatedAt"
    );

    private final ReservationRepository reservationRepository;
    private final BookRepository bookRepository;
    private final UserRepository userRepository;
    private final ReservationMapper reservationMapper;
    private final BookLoanRepository bookLoanRepository;
    private final FineRepository fineRepository;
    private final BookLoanService bookLoanService;
    private final NotificationService notificationService;
    private final SubscriptionService subscriptionService;
    private final ReservationQueueService reservationQueueService;


    @Override
    @Transactional
    public ReservationDTO createReservation(ReservationRequest reservationRequest)
        throws ReservationException, BookException, UserException {
        User user = getCurrentUser();
        return createReservationForUser(user.getId(), reservationRequest);
    }

    @Override
    @Transactional
    public ReservationDTO createReservationForUser(
            Long userId,
            ReservationRequest reservationRequest)
        throws ReservationException, BookException, UserException {

        boolean alreadyHasLoan = bookLoanRepository
                .existsByUserIdAndBookIdAndStatus(
                        userId, reservationRequest.getBookId(),
                        BookLoanStatus.CHECKED_OUT);

        if(alreadyHasLoan) {
            throw new BookException("You already have loan On this book!");
        }


        User user = userRepository.findById(userId)
            .orElseThrow(() -> new UserException("User not found with ID: " + userId));

        // Block reservations when any fine is still unpaid (overdue/damage/loss).
        if (fineRepository.hasUnpaidFines(userId)) {
            throw new ReservationException(
                    "You have unpaid fines (overdue/damage/loss). Please clear all pending fines before making a reservation."
            );
        }

        if (!subscriptionService.hasValidSubscription(userId)) {
            throw new ReservationException("Active subscription required to reserve a book");
        }

        Book book = bookRepository.findById(reservationRequest.getBookId())
            .orElseThrow(() -> new BookException("Book not found with ID: " + reservationRequest.getBookId()));

        if (!book.getActive()) {
            throw new BookException("Book is not active");
        }

        if (reservationRepository.hasActiveReservation(userId, book.getId())) {
            throw new ReservationException("You already have an active reservation for this book");
        }

        if (book.getAvailableCopies() > 0) {
            throw new ReservationException("Book is currently available. Please check it out directly instead of reserving.");
        }

        long activeReservations = reservationRepository.countActiveReservationsByUser(userId);
        if (activeReservations >= MAX_ACTIVE_RESERVATIONS) {
            throw new ReservationException("Maximum active reservations limit reached (" + MAX_ACTIVE_RESERVATIONS + ")");
        }

        Reservation reservation = new Reservation();
        reservation.setUser(user);
        reservation.setBook(book);
        reservation.setStatus(ReservationStatus.PENDING);
        reservation.setReservedAt(LocalDateTime.now());
        reservation.setNotificationSent(false);
        reservation.setNotes(reservationRequest.getNotes());

        // Calculate queue position
        long pendingCount = reservationRepository
        .countPendingReservationsByBook(book.getId());
        reservation.setQueuePosition((int) (pendingCount + 1));

        Reservation savedReservation = reservationRepository.save(reservation);

        notificationService.createNotification(
                user,
                "Reservation Created",
                "Your reservation for \"" + book.getTitle() + "\" is confirmed. We will notify you once it becomes available.",
                NotificationType.RESERVATION_CREATED,
                savedReservation.getId()
        );

        logger.info("Reservation created for user {} and book {} (Queue position: {})",
            userId, book.getId(), reservation.getQueuePosition());

        return reservationMapper.toDTO(savedReservation);
    }

    @Override
    @Transactional
    public ReservationDTO cancelReservation(Long reservationId) throws ReservationException {
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow(() -> new ReservationException("Reservation not found with ID: " + reservationId));

        // Verify current user owns this reservation (unless admin)
        User currentUser = getCurrentUser();
        if (
                !reservation.getUser().getId().equals(currentUser.getId())
                        && currentUser.getRole()!= UserRole.ROLE_ADMIN
        ) {

            throw new ReservationException("You can only cancel your own reservations");
        }

        if (!reservation.canBeCancelled()) {
            throw new ReservationException("Reservation cannot be cancelled (current status: " + reservation.getStatus() + ")");
        }

        boolean wasAvailable = reservation.getStatus() == ReservationStatus.AVAILABLE;

        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelledAt(LocalDateTime.now());

        Reservation savedReservation = reservationRepository.save(reservation);

        notificationService.createNotification(
                currentUser,
                "Reservation Cancelled",
                "Your reservation for \"" + reservation.getBook().getTitle() + "\" has been cancelled.",
                NotificationType.RESERVATION_CANCELLED,
                savedReservation.getId()
        );

        if (wasAvailable) {
            // A held slot was released, so promote next queued user(s) if copies are still available.
            processNextReservation(reservation.getBook().getId());
        } else {
            updateQueuePositions(reservation.getBook().getId());
        }

        logger.info("Reservation {} cancelled by user {}", reservationId, currentUser.getId());

        return reservationMapper.toDTO(savedReservation);
    }

    @Override
    @Transactional
    public ReservationDTO fulfillReservation(Long reservationId) throws ReservationException, BookException, UserException {
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow(() -> new ReservationException("Reservation not found with ID: " + reservationId));

        User currentUser = getCurrentUser();
        if (!reservation.getUser().getId().equals(currentUser.getId())
                && currentUser.getRole() != UserRole.ROLE_ADMIN) {
            throw new ReservationException("You can only checkout your own reservation");
        }

        if (reservation.getStatus() != ReservationStatus.AVAILABLE) {
            throw new ReservationException("Reservation is not ready for checkout (current status: " + reservation.getStatus() + ")");
        }

        LocalDateTime now = LocalDateTime.now();
        if (reservation.getAvailableUntil() == null || now.isAfter(reservation.getAvailableUntil())) {
            reservation.setStatus(ReservationStatus.EXPIRED);
            reservation.setCancelledAt(now);
            reservationRepository.save(reservation);
            processNextReservation(reservation.getBook().getId());
            throw new ReservationException("Reservation hold window has expired. Please reserve again.");
        }

        if (reservation.getBook().getAvailableCopies() <= 0) {
            throw new ReservationException("Book copy is no longer available. Please wait for next availability notification.");
        }

        CheckoutRequest request = new CheckoutRequest();
        request.setBookId(reservation.getBook().getId());
        request.setNotes("Checked out from reservation hold");
        bookLoanService.checkoutBookForUser(reservation.getUser().getId(), request);

        reservation.setStatus(ReservationStatus.FULFILLED);
        reservation.setFulfilledAt(now);
        Reservation savedReservation = reservationRepository.save(reservation);

        // Keep queue position semantics clear once fulfilled.
        updateQueuePositions(reservation.getBook().getId());

        notificationService.createNotification(
                reservation.getUser(),
                "Reserved Book Checked Out",
                "Your reservation for \"" + reservation.getBook().getTitle() + "\" has been fulfilled successfully.",
                NotificationType.RESERVATION_FULFILLED,
                savedReservation.getId()
        );

        logger.info("Reservation {} fulfilled by user {}", reservationId, currentUser.getId());

        return reservationMapper.toDTO(savedReservation);
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationDTO getReservationById(Long reservationId) throws ReservationException {
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow(() -> new ReservationException("Reservation not found with ID: " + reservationId));

        return reservationMapper.toDTO(reservation);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ReservationDTO> searchReservations(ReservationSearchRequest searchRequest) {
        if (searchRequest == null) {
            searchRequest = new ReservationSearchRequest();
        }

        Pageable pageable = createPageable(searchRequest);

        boolean applyStatuses = searchRequest.getStatuses() != null && !searchRequest.getStatuses().isEmpty();
        List<ReservationStatus> statuses = applyStatuses
                ? searchRequest.getStatuses()
                : List.of(ReservationStatus.PENDING);

        boolean applyFromDate = searchRequest.getFromDate() != null;
        boolean applyToDate = searchRequest.getToDate() != null;

        LocalDateTime fromDateTime = applyFromDate
                ? searchRequest.getFromDate().atStartOfDay()
                : LocalDateTime.of(1970, 1, 1, 0, 0);
        LocalDateTime toDateTime = applyToDate
                ? searchRequest.getToDate().atTime(23, 59, 59, 999_999_999)
                : LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_999);

        if (applyFromDate && applyToDate && fromDateTime.isAfter(toDateTime)) {
            LocalDateTime temp = fromDateTime;
            fromDateTime = toDateTime;
            toDateTime = temp;
        }

        Page<Reservation> reservationPage = reservationRepository.searchReservationsWithFilters(
            searchRequest.getUserId(),
            searchRequest.getBookId(),
            searchRequest.getStatus(),
            applyStatuses,
            statuses,
            searchRequest.getActiveOnly() != null ? searchRequest.getActiveOnly() : false,
            applyFromDate,
            fromDateTime,
            applyToDate,
            toDateTime,
            pageable
        );

        return buildPageResponse(reservationPage);
    }

    @Override
    @Transactional
    public PageResponse<ReservationDTO> getMyReservations(ReservationSearchRequest searchRequest) {
        if (searchRequest == null) {
            searchRequest = new ReservationSearchRequest();
        }

        // Ensure users see AVAILABLE reservations as soon as copies are available,
        // even if availability changed outside normal return flow.
        processPendingReservationsForAvailableBooks();

        User user = getCurrentUser();
        searchRequest.setUserId(user.getId()); // Override userId with current user
        return searchReservations(searchRequest);
    }

    @Override
    @Transactional(readOnly = true)
    public int getQueuePosition(Long reservationId) throws ReservationException {
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow(() -> new ReservationException("Reservation not found with ID: " + reservationId));

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            return 0; // Not in queue
        }

        return reservation.getQueuePosition() != null ? reservation.getQueuePosition() : 0;
    }

    @Override
    @Transactional
    public void processNextReservation(Long bookId) {
        reservationQueueService.processNextReservation(bookId);
    }

    @Override
    @Transactional
    public int expireOldReservations() {
        logger.info("Starting to expire old reservations");

        List<Reservation> expiredReservations = reservationRepository.findExpiredReservations(LocalDateTime.now());
        Set<Long> booksToReprocess = new HashSet<>();

        for (Reservation reservation : expiredReservations) {
            reservation.setStatus(ReservationStatus.EXPIRED);
            reservation.setCancelledAt(LocalDateTime.now());
            reservationRepository.save(reservation);
            booksToReprocess.add(reservation.getBook().getId());

            notificationService.createNotification(
                    reservation.getUser(),
                    "Reservation Expired",
                    "Your reservation for \"" + reservation.getBook().getTitle() + "\" expired because it was not checked out within "
                            + HOLD_PERIOD_HOURS
                            + " hours.",
                    NotificationType.RESERVATION_EXPIRED,
                    reservation.getId()
            );
        }

        for (Long bookId : booksToReprocess) {
            processNextReservation(bookId);
        }

        logger.info("Expired {} reservation(s)", expiredReservations.size());
        return expiredReservations.size();
    }

    @Override
    @Transactional
    public int processPendingReservationsForAvailableBooks() {
        int promoted = 0;
        List<Long> booksWithPendingReservations = reservationRepository.findDistinctBookIdsByStatus(ReservationStatus.PENDING);

        for (Long bookId : booksWithPendingReservations) {
            promoted += reservationQueueService.processNextReservation(bookId);
        }

        if (promoted > 0) {
            logger.info("Promoted {} reservation(s) to AVAILABLE due to copy availability", promoted);
        }
        return promoted;
    }

    @Override
    @Transactional
    public void updateQueuePositions(Long bookId) {
        List<Reservation> pendingReservations = reservationRepository.findPendingReservationsByBook(bookId);

        int position = 1;
        for (Reservation reservation : pendingReservations) {
            reservation.setQueuePosition(position++);
            reservationRepository.save(reservation);
        }

        logger.info("Updated queue positions for {} reservation(s) of book ID: {}", pendingReservations.size(), bookId);
    }

    private User getCurrentUser() {
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

    private PageResponse<ReservationDTO> buildPageResponse(Page<Reservation> reservationPage) {
        List<ReservationDTO> dtos = reservationPage.getContent().stream()
            .map(reservationMapper::toDTO)
            .toList();

        PageResponse<ReservationDTO> response = new PageResponse<>();
        response.setContent(dtos);
        response.setPageNumber(reservationPage.getNumber());
        response.setPageSize(reservationPage.getSize());
        response.setTotalElements(reservationPage.getTotalElements());
        response.setTotalPages(reservationPage.getTotalPages());
        response.setLast(reservationPage.isLast());

        return response;
    }

    private Pageable createPageable(ReservationSearchRequest searchRequest) {
        int page = Math.max(searchRequest.getPage(), 0);
        int size = Math.max(Math.min(searchRequest.getSize(), 100), 1);
        String sortBy = ALLOWED_SORT_FIELDS.contains(searchRequest.getSortBy()) ? searchRequest.getSortBy() : "reservedAt";
        String sortDirection = searchRequest.getSortDirection() == null ? "DESC" : searchRequest.getSortDirection();

        Sort sort = "ASC".equalsIgnoreCase(sortDirection)
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        return PageRequest.of(page, size, sort);
    }

}


