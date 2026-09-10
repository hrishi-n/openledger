package dev.openledger.reconciliation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationJob {

    private final ReconciliationService service;

    public ReconciliationJob(ReconciliationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${openledger.reconciliation.cron}")
    public void runScheduled() {
        service.run();
    }
}
