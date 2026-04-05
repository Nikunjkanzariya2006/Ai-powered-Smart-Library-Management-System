package com.nik.service;

import com.nik.model.NotificationSettings;
import com.nik.model.User;
import com.nik.payload.dto.NotificationSettingsDTO;
import com.nik.payload.request.UpdateNotificationSettingsRequest;

/**
 * Service interface for managing notification settings
 */
public interface NotificationSettingsService {

    /**
     * Get notification settings for a user
     * @param user The user
     * @return NotificationSettings or null if not found
     */
    NotificationSettingsDTO getSettings(User user);

    /**
     * Get notification settings for a user, creating default settings if they don't exist
     * @param user The user
     * @return NotificationSettings
     */
    NotificationSettings getOrCreateSettings(User user);

    /**
     * Update notification settings for a user
     * @param user The user
     * @param request The update request
     * @return Updated NotificationSettings
     */
    NotificationSettingsDTO updateSettings(User user, UpdateNotificationSettingsRequest request);

    /**
     * Create default notification settings for a user
     * @param user The user
     * @return Created NotificationSettings
     */
    NotificationSettings createDefaultSettings(User user);
}

