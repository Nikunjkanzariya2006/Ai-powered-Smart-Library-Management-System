package com.nik.event.listener;

import com.nik.event.PaymentSuccessEvent;
import com.nik.exception.SubscriptionException;
import com.nik.service.FineService;
import com.nik.service.SubscriptionService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventListener {

    private final FineService fineService;
    private final SubscriptionService subscriptionService;


    @EventListener
    @Transactional
    public void handlePaymentSuccess(PaymentSuccessEvent event)
            throws SubscriptionException {
        log.info("Received PaymentSuccessEvent: paymentId={}, type={}, amount={}",
                event.getPaymentId(), event.getPaymentType(), event.getAmount());

        switch (event.getPaymentType()) {
            case FINE:
                fineService.markFineAsPaid(
                        event.getFineId(),
                        event.getAmount(),
                        event.getTransactionId()
                );
                break;

            case SUBSCRIPTION:
                subscriptionService.activateSubscription(event.getSubscriptionId(), event.getPaymentId());
                break;

            default:
                log.warn("Unhandled payment type: {}", event.getPaymentType());
        }
    }
}
