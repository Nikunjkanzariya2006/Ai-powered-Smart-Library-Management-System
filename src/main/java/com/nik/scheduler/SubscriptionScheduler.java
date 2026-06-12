package com.nik.scheduler;

import com.nik.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled tasks for subscription management
 * Runs automatic subscription expiry checks
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionScheduler {

    private final SubscriptionService subscriptionService;

    /**
     * Deactivate expired subscriptions daily at 2 AM
     * Cron expression: "0 0 2 * * ?" = Every day at 2:00 AM
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @SchedulerLock(name = "SubscriptionScheduler_deactivateExpiredSubscriptions", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void deactivateExpiredSubscriptions() {
        log.info("Starting scheduled subscription expiry check...");

        try {
            subscriptionService.deactivateExpiredSubscriptions();
            log.info("Scheduled subscription expiry check completed successfully");
        } catch (Exception e) {
            log.error("Error during scheduled subscription expiry check", e);
        }
    }

    /**
     * Alternative: Run every hour (for testing/high-frequency checks)
     * Uncomment if you want hourly checks instead of daily
     */
    // @Scheduled(fixedRate = 3600000) // Every hour (3600000 ms)
    // public void deactivateExpiredSubscriptionsHourly() {
    //     log.info("Starting hourly subscription expiry check...");
    //
    //     try {
    //         subscriptionService.deactivateExpiredSubscriptions();
    //         log.info("Hourly subscription expiry check completed successfully");
    //     } catch (Exception e) {
    //         log.error("Error during hourly subscription expiry check", e);
    //     }
    // }
}
