package com.nik.payload.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateNotificationSettingsRequest {
    private Boolean emailEnabled;
    private Boolean reservationNotificationsEnabled;
    private Boolean reservationCreatedNotificationsEnabled;
    private Boolean reservationAvailableNotificationsEnabled;
    private Boolean reservationFulfilledNotificationsEnabled;
}
