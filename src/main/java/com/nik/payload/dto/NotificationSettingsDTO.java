package com.nik.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationSettingsDTO {
    private Long id;
    private Long userId;
    private Boolean emailEnabled;
    private Boolean reservationNotificationsEnabled;
    private Boolean reservationCreatedNotificationsEnabled;
    private Boolean reservationAvailableNotificationsEnabled;
    private Boolean reservationFulfilledNotificationsEnabled;
    private LocalDateTime updatedAt;
}
