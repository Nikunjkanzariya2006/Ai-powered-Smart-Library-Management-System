package com.nik.service.impl;

import com.nik.model.Notification;
import com.nik.model.NotificationSettings;
import com.nik.model.PushToken;
import com.nik.model.User;
import com.nik.repository.PushTokenRepository;
import com.nik.service.NotificationDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryServiceImpl implements NotificationDeliveryService {

    private final PushTokenRepository pushTokenRepository;

    @Override
    public void deliverNotification(Notification notification, NotificationSettings settings) {
        log.debug("Delivering notification {} to user {}", notification.getId(), notification.getUser().getEmail());

        log.debug("In-app notification saved for user: {}", notification.getUser().getEmail());

        if (settings.getEmailEnabled() && shouldSendEmail(notification, settings)) {
            try {
                sendEmail(notification.getUser(), notification.getTitle(), notification.getMessage());
            } catch (Exception e) {
                log.error("Failed to send email notification to user {}: {}",
                         notification.getUser().getEmail(), e.getMessage());
            }
        }

        if (settings.getPushEnabled() && shouldSendPush(notification, settings)) {
            try {
                sendPush(notification.getUser(), notification.getTitle(), notification.getMessage());
            } catch (Exception e) {
                log.error("Failed to send push notification to user {}: {}",
                         notification.getUser().getEmail(), e.getMessage());
            }
        }
    }

    @Override
    public void sendEmail(User user, String title, String message) {
        log.info("EMAIL NOTIFICATION - To: {}, Subject: {}, Message: {}",
                 user.getEmail(), title, message);
    }

    @Override
    public void sendPush(User user, String title, String message) {
        List<PushToken> activeTokens = pushTokenRepository.findByUserAndIsActiveTrue(user);

        if (activeTokens.isEmpty()) {
            log.debug("No active push tokens found for user: {}", user.getEmail());
            return;
        }

        log.info("PUSH NOTIFICATION - User: {}, Tokens: {}, Title: {}, Message: {}",
                 user.getEmail(), activeTokens.size(), title, message);

        for (PushToken token : activeTokens) {
            log.debug("Would send push to token: {} ({})",
                     token.getToken().substring(0, Math.min(20, token.getToken().length())) + "...",
                     token.getPlatform());
        }
    }

    private boolean shouldSendEmail(Notification notification, NotificationSettings settings) {
        return switch (notification.getType()) {
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
            case FINE_NOTIFICATION -> true;
            case BOOK_RETURNED -> settings.getBookRemindersEnabled();
            case PAYMENT_FAILED,
                 SYSTEM_NOTIFICATION -> true;
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

    private boolean shouldSendPush(Notification notification, NotificationSettings settings) {
        return shouldSendEmail(notification, settings);
    }
}

