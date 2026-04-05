package com.nik.payload.request;

import jakarta.validation.constraints.AssertTrue;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentVerifyRequest {
    private String razorpayPaymentId;
    private String razorpayOrderId;
    private String razorpaySignature;

    @AssertTrue(message = "Razorpay payment reference is required")
    public boolean hasGatewayReference() {
        return StringUtils.hasText(razorpayPaymentId);
    }
}
