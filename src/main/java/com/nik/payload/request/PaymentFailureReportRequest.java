package com.nik.payload.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentFailureReportRequest {

    @NotBlank(message = "Gateway reference is required")
    private String gatewayReference;

    private String failureReason;
}
