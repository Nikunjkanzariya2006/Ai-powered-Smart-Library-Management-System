package com.nik.service.impl;

import com.nik.mapper.NotificationSettingsMapper;
import com.nik.model.NotificationSettings;
import com.nik.model.User;
import com.nik.payload.dto.NotificationSettingsDTO;
import com.nik.payload.request.UpdateNotificationSettingsRequest;
import com.nik.repository.NotificationSettingsRepository;
import com.nik.service.NotificationSettingsService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Implementation of NotificationSettingsService interface.
 * Handles notification settings management for users.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class NotificationSettingsServiceImpl implements NotificationSettingsService {

    private final NotificationSettingsRepository notificationSettingsRepository;

    @Override
    public NotificationSettingsDTO getSettings(User user) {
        log.debug("Fetching notification settings for user: {}", user.getEmail());
        NotificationSettings settings = getOrCreateSettings(user);
        return NotificationSettingsMapper.toDTO(settings);
    }

    @Override
    public NotificationSettings getOrCreateSettings(User user) {
        log.debug("Fetching or creating notification settings for user: {}", user.getEmail());
        return notificationSettingsRepository.findByUser(user)
                .orElseGet(() -> createDefaultSettings(user));
    }

    @Override
    public NotificationSettingsDTO updateSettings(User user, UpdateNotificationSettingsRequest request) {
        log.info("Updating notification settings for user: {}", user.getEmail());

        NotificationSettings settings = getOrCreateSettings(user);

        // Update only non-null fields from the request
        if (request.getEmailEnabled() != null) {
            settings.setEmailEnabled(request.getEmailEnabled());
        }
        Boolean legacyReservationToggle = request.getReservationNotificationsEnabled();
        Boolean createdToggle = request.getReservationCreatedNotificationsEnabled();
        Boolean availableToggle = request.getReservationAvailableNotificationsEnabled();
        Boolean fulfilledToggle = request.getReservationFulfilledNotificationsEnabled();

        if (legacyReservationToggle != null) {
            settings.setReservationNotificationsEnabled(legacyReservationToggle);
            // Backward compatibility for older clients that only send one reservation toggle.
            if (createdToggle == null && availableToggle == null && fulfilledToggle == null) {
                createdToggle = legacyReservationToggle;
                availableToggle = legacyReservationToggle;
                fulfilledToggle = legacyReservationToggle;
            }
        }

        if (createdToggle != null) {
            settings.setReservationCreatedNotificationsEnabled(createdToggle);
        }
        if (availableToggle != null) {
            settings.setReservationAvailableNotificationsEnabled(availableToggle);
        }
        if (fulfilledToggle != null) {
            settings.setReservationFulfilledNotificationsEnabled(fulfilledToggle);
        }

        NotificationSettings savedSettings = notificationSettingsRepository.save(settings);
        log.info("Successfully updated notification settings for user: {}", user.getEmail());

        return NotificationSettingsMapper.toDTO(savedSettings);
    }

    @Override
    public NotificationSettings createDefaultSettings(User user) {
        log.info("Creating default notification settings for user: {}", user.getEmail());

        NotificationSettings settings = NotificationSettings.builder()
                .user(user)
                .emailEnabled(true)
                .pushEnabled(false)
                .bookRemindersEnabled(false)
                .dueDateAlertsEnabled(false)
                .newArrivalsEnabled(false)
                .recommendationsEnabled(false)
                .marketingEmailsEnabled(false)
                .reservationNotificationsEnabled(true)
                .reservationCreatedNotificationsEnabled(true)
                .reservationAvailableNotificationsEnabled(true)
                .reservationFulfilledNotificationsEnabled(true)
                .subscriptionNotificationsEnabled(false)
                .build();

        NotificationSettings savedSettings = notificationSettingsRepository.save(settings);
        log.info("Successfully created default notification settings for user: {}", user.getEmail());

        return savedSettings;
    }
}

