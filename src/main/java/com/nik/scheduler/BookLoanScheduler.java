package com.nik.scheduler;

import com.nik.service.BookLoanService;
import com.nik.service.NotificationService;
import com.nik.service.ReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled tasks for book loan operations.
 * Automatically updates overdue book loans, calculates fines, and sends notifications.
 */
@Component
public class BookLoanScheduler {

    private static final Logger logger = LoggerFactory.getLogger(BookLoanScheduler.class);
    private static final int DUE_DATE_REMINDER_DAYS = 3; // Send reminder 3 days before due date

    private final BookLoanService bookLoanService;
    private final NotificationService notificationService;
    private final ReservationService reservationService;

    public BookLoanScheduler(BookLoanService bookLoanService,
                            NotificationService notificationService,
                            ReservationService reservationService) {
        this.bookLoanService = bookLoanService;
        this.notificationService = notificationService;
        this.reservationService = reservationService;
    }

    /**
     * Scheduled task to mark overdue book loans and calculate fines.
     * Runs every midnight (00:00:00).
     */
    @Scheduled(cron = "0 0 0 * * ?")
    @SchedulerLock(name = "BookLoanScheduler_markOverdueLoans", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void markOverdueLoans() {
        logger.info("Starting scheduled task: marking overdue book loans");

        try {
            int updatedCount = bookLoanService.updateOverdueBookLoans();
            logger.info("Successfully marked {} book loan(s) as overdue", updatedCount);
        } catch (Exception e) {
            logger.error("Error occurred while marking overdue book loans", e);
        }
    }

    /**
     * Scheduled task to send overdue notifications.
     * Runs every day at 9:00 AM.
     */
    @Scheduled(cron = "0 0 9 * * ?")
    @SchedulerLock(name = "BookLoanScheduler_sendOverdueNotifications", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void sendOverdueNotifications() {
        logger.info("Starting scheduled task: sending overdue notifications");

        try {
            int notificationsSent = notificationService.sendOverdueNotifications();
            logger.info("Successfully sent {} overdue notification(s)", notificationsSent);
        } catch (Exception e) {
            logger.error("Error occurred while sending overdue notifications", e);
        }
    }

    /**
     * Scheduled task to send due date reminders.
     * Runs every day at 10:00 AM.
     * Sends reminders for books due in 3 days.
     */
    @Scheduled(cron = "0 0 10 * * ?")
    @SchedulerLock(name = "BookLoanScheduler_sendDueDateReminders", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void sendDueDateReminders() {
        logger.info("Starting scheduled task: sending due date reminders");

        try {
            int notificationsSent = notificationService.sendDueDateReminders(DUE_DATE_REMINDER_DAYS);
            logger.info("Successfully sent {} due date reminder(s)", notificationsSent);
        } catch (Exception e) {
            logger.error("Error occurred while sending due date reminders", e);
        }
    }

    /**
     * Scheduled task to expire old reservations.
     * Runs every hour so reservation expiry is enforced close to the 24-hour hold window.
     */
    @Scheduled(cron = "0 0 * * * ?")
    @SchedulerLock(name = "BookLoanScheduler_expireOldReservations", lockAtMostFor = "5m", lockAtLeastFor = "1m")
    public void expireOldReservations() {
        logger.info("Starting scheduled task: expiring old reservations");

        try {
            int expiredCount = reservationService.expireOldReservations();
            logger.info("Successfully expired {} reservation(s)", expiredCount);
        } catch (Exception e) {
            logger.error("Error occurred while expiring old reservations", e);
        }
    }

    /**
     * Safety-net job:
     * If a book has available copies and pending reservations, mark next reservation as AVAILABLE
     * and notify user to checkout within hold window.
     * Runs every 5 minutes.
     */
    @Scheduled(cron = "0 */5 * * * ?")
    @SchedulerLock(name = "BookLoanScheduler_promotePendingReservationsWhenBookIsAvailable", lockAtMostFor = "4m", lockAtLeastFor = "1m")
    public void promotePendingReservationsWhenBookIsAvailable() {
        try {
            int promoted = reservationService.processPendingReservationsForAvailableBooks();
            if (promoted > 0) {
                logger.info("Promoted {} pending reservation(s) to AVAILABLE", promoted);
            }
        } catch (Exception e) {
            logger.error("Error occurred while promoting pending reservations", e);
        }
    }
}
