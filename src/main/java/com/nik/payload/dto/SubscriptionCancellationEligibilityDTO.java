package com.nik.payload.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionCancellationEligibilityDTO {
    private boolean canCancel;
    private List<String> blockingStatuses;
    private String message;
}
