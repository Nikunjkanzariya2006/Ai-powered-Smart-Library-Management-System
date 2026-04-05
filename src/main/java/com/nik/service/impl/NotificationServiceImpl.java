package com.nik.service.impl;

import com.nik.domain.BookLoanStatus;
import com.nik.domain.NotificationType;
import com.nik.domain.PaymentType;
import com.nik.domain.UserRole;
import com.nik.event.PaymentFailedEvent;
import com.nik.event.PaymentInitiatedEvent;
import com.nik.event.PaymentSuccessEvent;
import com.nik.exception.BookLoanException;
import com.nik.exception.UserException;
import com.nik.mapper.NotificationMapper;
import com.nik.model.BookLoan;
import com.nik.model.Notification;
import com.nik.model.NotificationSettings;
import com.nik.model.User;
import com.nik.payload.dto.NotificationDTO;
import com.nik.repository.BookLoanRepository;
import com.nik.repository.NotificationRepository;
import com.nik.repository.UserRepository;
import com.nik.service.EmailService;
import com.nik.service.NotificationDeliveryService;
import com.nik.service.NotificationService;
import com.nik.service.NotificationSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Implementation of NotificationService for managing all types of notifications.
 * Handles book loan notifications, user notifications, and notification settings.
 */
@Service
@Transactional
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM dd, yyyy");
    private static final int BATCH_SIZE = 50;

    private final BookLoanRepository bookLoanRepository;
    private final EmailService emailService;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final NotificationSettingsService notificationSettingsService;
    private final NotificationDeliveryService notificationDeliveryService;

    public NotificationServiceImpl(BookLoanRepository bookLoanRepository,
                                  EmailService emailService,
                                  NotificationRepository notificationRepository,
                                  UserRepository userRepository,
                                  NotificationSettingsService notificationSettingsService,
                                  NotificationDeliveryService notificationDeliveryService) {
        this.bookLoanRepository = bookLoanRepository;
        this.emailService = emailService;
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.notificationSettingsService = notificationSettingsService;
        this.notificationDeliveryService = notificationDeliveryService;
    }

    @Override
    @Transactional(readOnly = true)
    public int sendOverdueNotifications() {
        log.info("Starting to send overdue notifications");
        int notificationsSent = 0;
        int pageNumber = 0;

        try {
            Page<BookLoan> bookLoanPage;
            do {
                Pageable pageable = PageRequest.of(pageNumber, BATCH_SIZE);
                bookLoanPage = bookLoanRepository.findOverdueBookLoans(LocalDate.now(), pageable);

                for (BookLoan bookLoan : bookLoanPage.getContent()) {
                    try {
                        sendOverdueNotificationInternal(bookLoan);
                        notificationsSent++;
                    } catch (Exception e) {
                        log.error("Failed to send overdue notification for book loan ID: {}", bookLoan.getId(), e);
                    }
                }

                pageNumber++;
            } while (bookLoanPage.hasNext());

            log.info("Successfully sent {} overdue notification(s)", notificationsSent);
        } catch (Exception e) {
            log.error("Error occurred while sending overdue notifications", e);
        }

        return notificationsSent;
    }

    @Override
    @Transactional(readOnly = true)
    public int sendDueDateReminders(int daysBeforeDue) {
        log.info("Starting to send due date reminders for books due in {} days", daysBeforeDue);
        int notificationsSent = 0;
        int pageNumber = 0;

        try {
            LocalDate targetDate = LocalDate.now().plusDays(daysBeforeDue);
            Page<BookLoan> bookLoanPage;

            do {
                Pageable pageable = PageRequest.of(pageNumber, BATCH_SIZE);
                bookLoanPage = bookLoanRepository.findBookLoansByDueDateAndStatus(
                    targetDate, BookLoanStatus.CHECKED_OUT, pageable
                );

                for (BookLoan bookLoan : bookLoanPage.getContent()) {
                    try {
                        sendDueDateReminderInternal(bookLoan);
                        notificationsSent++;
                    } catch (Exception e) {
                        log.error("Failed to send due date reminder for book loan ID: {}", bookLoan.getId(), e);
                    }
                }

                pageNumber++;
            } while (bookLoanPage.hasNext());

            log.info("Successfully sent {} due date reminder(s)", notificationsSent);
        } catch (Exception e) {
            log.error("Error occurred while sending due date reminders", e);
        }

        return notificationsSent;
    }

    @Override
    @Transactional(readOnly = true)
    public void sendOverdueNotification(Long bookLoanId) {
        BookLoan bookLoan = bookLoanRepository.findById(bookLoanId)
            .orElseThrow(() -> new BookLoanException("Book loan not found with ID: " + bookLoanId));

        if (bookLoan.getIsOverdue()) {
            sendOverdueNotificationInternal(bookLoan);
        } else {
            log.warn("Book loan ID {} is not overdue. Notification not sent.", bookLoanId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void sendDueDateReminder(Long bookLoanId) {
        BookLoan bookLoan = bookLoanRepository.findById(bookLoanId)
            .orElseThrow(() -> new BookLoanException("Book loan not found with ID: " + bookLoanId));

        if (bookLoan.getStatus() == BookLoanStatus.CHECKED_OUT && !bookLoan.getIsOverdue()) {
            sendDueDateReminderInternal(bookLoan);
        } else {
            log.warn("Book loan ID {} is not eligible for due date reminder. Current status: {}",
                bookLoanId, bookLoan.getStatus());
        }
    }

//    @Override
//    public Page<NotificationDTO> getUserNotifications(User user, Pageable pageable) {
//        return null;
//    }

//    @Override
//    public Page<NotificationDTO> getUnreadNotifications(User user, Pageable pageable) {
//        return null;
//    }

    /**
     * Get all notifications for a user (paginated)
     */
    @Override
    public Page<NotificationDTO> getUserNotifications(User user, int page, int size) {
        log.debug("Fetching notifications for user: {}, page: {}, size: {}", user.getEmail(), page, size);
        Pageable pageable = PageRequest.of(page, size);
        Page<Notification> notifications =
                notificationRepository
                        .findByUserOrderByCreatedAtDesc(user, pageable);
        return notifications.map(NotificationMapper::toDTO);
    }

    /**
     * Get unread notifications for a user (paginated)
     */
    public Page<Notification> getUnreadNotifications(User user, int page, int size) {
        log.debug("Fetching unread notifications for user: {}, page: {}, size: {}", user.getEmail(), page, size);
        Pageable pageable = PageRequest.of(page, size);
        return notificationRepository.findByUserAndIsReadFalseOrderByCreatedAtDesc(user, pageable);
    }

    /**
     * Get count of unread notifications for a user
     */
    public Long getUnreadCount(User user) {
        log.debug("Fetching unread count for user: {}", user.getEmail());
        return notificationRepository.countByUserAndIsReadFalse(user);
    }

    @Override
    public NotificationDTO markAsRead(Long notificationId, User user) throws UserException {
        Notification notification = markAsRead(user, notificationId);
        return NotificationMapper.toDTO(notification);
    }

    /**
     * Create a new notification
     * Checks notification settings before creating and triggers delivery
     */
    public Notification createNotification(User user, String title, String message,
                                          NotificationType type, Long relatedEntityId) {
        log.info("Creating notification for user: {}, type: {}", user.getEmail(), type);

        // Get or create user's notification settings
        NotificationSettings settings = notificationSettingsService.getOrCreateSettings(user);

        // Check if this type of notification is enabled
        if (!isNotificationTypeEnabled(type, settings)) {
            log.debug("Notification type {} is disabled for user: {}", type, user.getEmail());
            return null;
        }

        // Create and save the notification
        Notification notification = Notification.builder()
                .user(user)
                .title(title)
                .message(message)
                .type(type)
                .relatedEntityId(relatedEntityId)
                .isRead(false)
                .build();

        Notification savedNotification = notificationRepository.save(notification);
        log.info("Successfully created notification {} for user: {}", savedNotification.getId(), user.getEmail());

        // Trigger delivery through various channels
        try {
            notificationDeliveryService.deliverNotification(savedNotification, settings);
        } catch (Exception e) {
            log.error("Failed to deliver notification {}: {}", savedNotification.getId(), e.getMessage(), e);
        }

        return savedNotification;
    }

    /**
     * Mark a notification as read
     */
    public Notification markAsRead(User user, Long notificationId) throws UserException {
        log.info("Marking notification {} as read for user: {}", notificationId, user.getEmail());

        Notification notification = notificationRepository.findByIdAndUser(notificationId, user)
                .orElseThrow(() -> new UserException("Notification not found or doesn't belong to user"));

        if (!notification.getIsRead()) {
            notification.setIsRead(true);
            notification.setReadAt(LocalDateTime.now());
            notification = notificationRepository.save(notification);
            log.info("Successfully marked notification {} as read", notificationId);
        } else {
            log.debug("Notification {} was already marked as read", notificationId);
        }

        return notification;
    }

    /**
     * Mark all notifications as read for a user
     */
    public void markAllAsRead(User user) {
        log.info("Marking all notifications as read for user: {}", user.getEmail());
        notificationRepository.markAllAsReadByUser(user);
        log.info("Successfully marked all notifications as read for user: {}", user.getEmail());
    }

    @Override
    public void deleteNotification(Long notificationId, User user) throws UserException {
        deleteNotification(user, notificationId);
    }

    /**
     * Delete a notification
     */
    public void deleteNotification(User user, Long notificationId) throws UserException {
        log.info("Deleting notification {} for user: {}", notificationId, user.getEmail());

        Notification notification = notificationRepository.findByIdAndUser(notificationId, user)
                .orElseThrow(() -> new UserException("Notification not found or doesn't belong to user"));

        notificationRepository.delete(notification);
        log.info("Successfully deleted notification {}", notificationId);
    }

    /**
     * Delete all notifications for a user
     */
    public void deleteAllNotifications(User user) {
        log.info("Deleting all notifications for user: {}", user.getEmail());
        notificationRepository.deleteAllByUser(user);
        log.info("Successfully deleted all notifications for user: {}", user.getEmail());
    }

    /**
     * Get a notification by ID (with user verification)
     */
    public Notification getNotificationById(User user, Long notificationId) throws UserException {
        log.debug("Fetching notification {} for user: {}", notificationId, user.getEmail());
        return notificationRepository.findByIdAndUser(notificationId, user)
                .orElseThrow(() -> new UserException("Notification not found or doesn't belong to user"));
    }

    /**
     * Helper method to check if a notification type is enabled based on settings
     */
    private boolean isNotificationTypeEnabled(NotificationType type, NotificationSettings settings) {
        return switch (type) {
            case DUE_DATE_ALERT -> settings.getDueDateAlertsEnabled();
            case BOOK_REMINDER -> settings.getBookRemindersEnabled();
            case NEW_ARRIVAL -> settings.getNewArrivalsEnabled();
            case RECOMMENDATION -> settings.getRecommendationsEnabled();
            case MARKETING -> settings.getMarketingEmailsEnabled();
            case RESERVATION_CREATED -> isReservationCreatedEnabled(settings);
            case RESERVATION_AVAILABLE -> isReservationAvailableEnabled(settings);
            case RESERVATION_FULFILLED -> isReservationFulfilledEnabled(settings);
            case RESERVATION_CANCELLED,
                 RESERVATION_EXPIRED -> isReservationMasterEnabled(settings);
            case SUBSCRIPTION_EXPIRING -> settings.getSubscriptionNotificationsEnabled();
            case FINE_NOTIFICATION -> true; // Always enabled for important notifications
            case BOOK_RETURNED -> settings.getBookRemindersEnabled();
            case PAYMENT_FAILED,
                 SYSTEM_NOTIFICATION -> true; // Always enabled for system notifications
        };
    }

    private boolean isReservationMasterEnabled(NotificationSettings settings) {
        return settings.getReservationNotificationsEnabled() == null
                || settings.getReservationNotificationsEnabled();
    }

    private boolean isReservationCreatedEnabled(NotificationSettings settings) {
        if (!isReservationMasterEnabled(settings)) {
            return false;
        }
        return settings.getReservationCreatedNotificationsEnabled() == null
                || settings.getReservationCreatedNotificationsEnabled();
    }

    private boolean isReservationAvailableEnabled(NotificationSettings settings) {
        if (!isReservationMasterEnabled(settings)) {
            return false;
        }
        return settings.getReservationAvailableNotificationsEnabled() == null
                || settings.getReservationAvailableNotificationsEnabled();
    }

    private boolean isReservationFulfilledEnabled(NotificationSettings settings) {
        if (!isReservationMasterEnabled(settings)) {
            return false;
        }
        return settings.getReservationFulfilledNotificationsEnabled() == null
                || settings.getReservationFulfilledNotificationsEnabled();
    }

    private void sendOverdueNotificationInternal(BookLoan bookLoan) {
        String userEmail = bookLoan.getUser().getEmail();
        String userName = bookLoan.getUser().getFullName();
        String bookTitle = bookLoan.getBook().getTitle();
        String dueDate = bookLoan.getDueDate().format(DATE_FORMATTER);
        int overdueDays = bookLoan.getOverdueDays();
        String fineAmount = formatCurrency(bookLoan.getTotalFineAmount());

        emailService.sendOverdueReminder(
            userEmail,
            userName,
            bookTitle,
            dueDate,
            overdueDays,
            fineAmount
        );

        log.info("Overdue notification sent to {} for book: {}", userEmail, bookTitle);
    }

    private void sendDueDateReminderInternal(BookLoan bookLoan) {
        String userEmail = bookLoan.getUser().getEmail();
        String userName = bookLoan.getUser().getFullName();
        String bookTitle = bookLoan.getBook().getTitle();
        String dueDate = bookLoan.getDueDate().format(DATE_FORMATTER);
        int daysUntilDue = (int) ChronoUnit.DAYS.between(LocalDate.now(), bookLoan.getDueDate());

        emailService.sendDueDateReminder(
            userEmail,
            userName,
            bookTitle,
            dueDate,
            daysUntilDue
        );

        log.info("Due date reminder sent to {} for book: {}", userEmail, bookTitle);
    }

    /**
     * Event listener for payment initiated events.
     * Sends email notification to user with payment link.
     *
     * @param event Payment initiated event
     */
    @EventListener
    @Async
    public void handlePaymentInitiated(PaymentInitiatedEvent event) {
        log.info("Received PaymentInitiatedEvent for payment ID: {}, type: {}",
            event.getPaymentId(), event.getPaymentType());

        try {
            String paymentTypeDisplay = getPaymentTypeDisplay(event.getPaymentType());
            String amountDisplay = formatCurrency(event.getAmount());

            // Send email with payment link
//            emailService.sendPaymentInitiatedEmail(
//                event.getUserEmail(),
//                event.getUserName(),
//                paymentTypeDisplay,
//                amountDisplay,
//                event.getCheckoutUrl(),
//                event.getTransactionId()
//            );

            log.info("Payment initiated notification sent to {}", event.getUserEmail());

        } catch (Exception e) {
            log.error("Failed to send payment initiated notification: {}", e.getMessage(), e);
        }
    }

    /**
     * Event listener for payment success events.
     * Sends email notification to user confirming payment.
     *
     * @param event Payment success event
     */
    @EventListener
    @Async
    public void handlePaymentSuccess(PaymentSuccessEvent event) {
        log.info("Received PaymentSuccessEvent for payment ID: {}, type: {}",
            event.getPaymentId(), event.getPaymentType());

        try {
            String paymentTypeDisplay = getPaymentTypeDisplay(event.getPaymentType());
            String amountDisplay = formatCurrency(event.getAmount());

            // Send success email
//            emailService.sendPaymentSuccessEmail(
//                event.getUserEmail(),
//                event.getUserName(),
//                paymentTypeDisplay,
//                amountDisplay,
//                event.getTransactionId(),
//                event.getCompletedAt()
//            );

            log.info("Payment success notification sent to {}");

        } catch (Exception e) {
            log.error("Failed to send payment success notification: {}", e.getMessage(), e);
        }
    }

    /**
     * Event listener for payment failed events.
     * Sends email notification to user about payment failure.
     *
     * @param event Payment failed event
     */
    @EventListener
    @Async
    public void handlePaymentFailed(PaymentFailedEvent event) {
        log.info("Received PaymentFailedEvent for payment ID: {}, type: {}",
            event.getPaymentId(), event.getPaymentType());

        try {
            String amountDisplay = formatAmountForAdmin(event.getAmount(), event.getCurrency());
            Set<User> admins = userRepository.findByRole(UserRole.ROLE_ADMIN);

            String paymentTypeDisplay = getPaymentTypeDisplay(event.getPaymentType());

            for (User admin : admins) {
                String title = "Payment Failed";
                String message = "User: "
                        + safeValue(event.getUserName())
                        + ", Email: "
                        + safeValue(event.getUserEmail())
                        + ", Amount: "
                        + amountDisplay
                        + ", Type: "
                        + paymentTypeDisplay
                        + "."
                        + (event.getTransactionId() != null && !event.getTransactionId().isBlank()
                        ? " Transaction: " + event.getTransactionId() + "."
                        : "")
                        + (event.getFailureReason() != null && !event.getFailureReason().isBlank()
                        ? " Reason: " + event.getFailureReason()
                        : "");

                createNotification(
                        admin,
                        title,
                        message,
                        NotificationType.PAYMENT_FAILED,
                        event.getPaymentId()
                );
            }

            log.info("Payment failed admin notifications sent to {} admin(s) for payment {}",
                    admins.size(), event.getPaymentId());

        } catch (Exception e) {
            log.error("Failed to process payment failed admin notification: {}", e.getMessage(), e);
        }
    }

    /**
     * Get human-readable display name for payment type
     */
    private String getPaymentTypeDisplay(PaymentType paymentType) {
        return switch (paymentType) {
            case SUBSCRIPTION -> "Library Subscription";
            case FINE -> "Fine Payment";
        };
    }

    private String formatCurrency(Long amount) {
        if (amount == null) {
            return "₹0.00";
        }
        return String.format("₹%.2f", amount.doubleValue());
    }

    private String formatAmountForAdmin(Long amount, String currency) {
        long normalizedAmount = amount == null ? 0L : amount;
        String normalizedCurrency = (currency == null || currency.isBlank()) ? "INR" : currency.trim().toUpperCase();

        if ("INR".equals(normalizedCurrency)) {
            return String.format("₹%.2f", (double) normalizedAmount);
        }

        return String.format("%s %.2f", normalizedCurrency, (double) normalizedAmount);
    }

    private String safeValue(String value) {
        return (value == null || value.isBlank()) ? "Unknown" : value;
    }
}


