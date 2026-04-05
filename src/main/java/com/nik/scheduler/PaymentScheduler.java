package com.nik.scheduler;

import com.nik.service.impl.PaymentServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentScheduler {

    private final PaymentServiceImpl paymentService;

    @Scheduled(fixedDelay = 30000)
    public void reconcileProcessingPayments() {
        log.debug("Reconciling stale processing payments");
        paymentService.reconcileStaleProcessingPayments();
    }
}
