package com.nik.mapper;

import com.nik.model.NotificationSettings;
import com.nik.payload.dto.NotificationSettingsDTO;
import org.springframework.stereotype.Component;

@Component
public class NotificationSettingsMapper {

    public static NotificationSettingsDTO toDTO(NotificationSettings settings) {
        if (settings == null) {
            return null;
        }

        boolean reservationBaseEnabled = settings.getReservationNotificationsEnabled() == null
                || settings.getReservationNotificationsEnabled();

        return NotificationSettingsDTO.builder()
                .id(settings.getId())
                .userId(settings.getUser() != null ? settings.getUser().getId() : null)
                .emailEnabled(settings.getEmailEnabled())
                .reservationNotificationsEnabled(reservationBaseEnabled)
                .reservationCreatedNotificationsEnabled(
                        settings.getReservationCreatedNotificationsEnabled() != null
                                ? settings.getReservationCreatedNotificationsEnabled()
                                : reservationBaseEnabled
                )
                .reservationAvailableNotificationsEnabled(
                        settings.getReservationAvailableNotificationsEnabled() != null
                                ? settings.getReservationAvailableNotificationsEnabled()
                                : reservationBaseEnabled
                )
                .reservationFulfilledNotificationsEnabled(
                        settings.getReservationFulfilledNotificationsEnabled() != null
                                ? settings.getReservationFulfilledNotificationsEnabled()
                                : reservationBaseEnabled
                )
                .updatedAt(settings.getUpdatedAt())
                .build();
    }
}

