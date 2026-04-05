package com.nik.service.impl;

import com.nik.domain.NotificationType;
import com.nik.domain.ReservationStatus;
import com.nik.model.Book;
import com.nik.model.NotificationSettings;
import com.nik.model.Reservation;
import com.nik.repository.BookRepository;
import com.nik.repository.ReservationRepository;
import com.nik.service.EmailService;
import com.nik.service.NotificationService;
import com.nik.service.NotificationSettingsService;
import com.nik.service.ReservationQueueService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class ReservationQueueServiceImpl implements ReservationQueueService {

    private static final Logger logger = LoggerFactory.getLogger(ReservationQueueServiceImpl.class);
    private static final int HOLD_PERIOD_HOURS = 24;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM dd, yyyy");

    private final ReservationRepository reservationRepository;
    private final BookRepository bookRepository;
    private final NotificationService notificationService;
    private final NotificationSettingsService notificationSettingsService;
    private final EmailService emailService;

    @Override
    @Transactional
    public int processNextReservation(Long bookId) {
        logger.info("Processing reservation queue for book ID: {}", bookId);
        return promoteReservationsToAvailable(bookId);
    }

    private int promoteReservationsToAvailable(Long bookId) {
        Book book = bookRepository.findById(bookId).orElse(null);
        if (book == null || book.getAvailableCopies() == null || book.getAvailableCopies() <= 0) {
            return 0;
        }

        long activeHolds = reservationRepository.countByBookIdAndStatus(bookId, ReservationStatus.AVAILABLE);
        int availableSlots = book.getAvailableCopies() - (int) activeHolds;
        if (availableSlots <= 0) {
            return 0;
        }

        var reservationsToPromote = reservationRepository.findByBookIdAndStatusOrderByReservedAtAsc(
                bookId,
                ReservationStatus.PENDING,
                PageRequest.of(0, availableSlots)
        );

        if (reservationsToPromote.isEmpty()) {
            return 0;
        }

        int promoted = 0;
        for (Reservation reservation : reservationsToPromote) {
            LocalDateTime availableAt = LocalDateTime.now();
            reservation.setStatus(ReservationStatus.AVAILABLE);
            reservation.setAvailableAt(availableAt);
            reservation.setAvailableUntil(availableAt.plusHours(HOLD_PERIOD_HOURS));
            reservation.setNotificationSent(false);
            reservation.setQueuePosition(0);

            reservationRepository.save(reservation);

            notificationService.createNotification(
                    reservation.getUser(),
                    "Reserved Book Available",
                    "Your reserved book \"" + reservation.getBook().getTitle() + "\" is now available. Please checkout within "
                            + HOLD_PERIOD_HOURS
                            + " hours, otherwise reservation will be cancelled automatically.",
                    NotificationType.RESERVATION_AVAILABLE,
                    reservation.getId()
            );

            sendAvailabilityNotification(reservation);
            promoted++;
        }

        updateQueuePositions(bookId);
        logger.info("Promoted {} reservation(s) to AVAILABLE for book {}", promoted, bookId);
        return promoted;
    }

    private void updateQueuePositions(Long bookId) {
        var pendingReservations = reservationRepository.findPendingReservationsByBook(bookId);

        int position = 1;
        for (Reservation reservation : pendingReservations) {
            reservation.setQueuePosition(position++);
            reservationRepository.save(reservation);
        }

        logger.info("Updated queue positions for {} reservation(s) of book ID: {}", pendingReservations.size(), bookId);
    }

    private void sendAvailabilityNotification(Reservation reservation) {
        try {
            NotificationSettings settings = notificationSettingsService.getOrCreateSettings(reservation.getUser());
            if (!shouldSendReservationAvailabilityEmail(settings)) {
                logger.info("Skipping availability email for reservation {} because notification settings are disabled",
                        reservation.getId());
                return;
            }

            emailService.sendReservationAvailableNotification(
                    reservation.getUser().getEmail(),
                    reservation.getUser().getFullName(),
                    reservation.getBook().getTitle(),
                    reservation.getAvailableUntil().format(DATE_FORMATTER),
                    HOLD_PERIOD_HOURS
            );

            reservation.setNotificationSent(true);
            reservationRepository.save(reservation);

            logger.info("Availability notification sent to {} for book: {}",
                    reservation.getUser().getEmail(), reservation.getBook().getTitle());
        } catch (Exception e) {
            logger.error("Failed to send availability notification for reservation: {}", reservation.getId(), e);
        }
    }

    private boolean shouldSendReservationAvailabilityEmail(NotificationSettings settings) {
        if (settings == null || Boolean.FALSE.equals(settings.getEmailEnabled())) {
            return false;
        }

        if (Boolean.FALSE.equals(settings.getReservationNotificationsEnabled())) {
            return false;
        }

        return !Boolean.FALSE.equals(settings.getReservationAvailableNotificationsEnabled());
    }
}
