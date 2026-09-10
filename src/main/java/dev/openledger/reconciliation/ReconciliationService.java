package dev.openledger.reconciliation;

import dev.openledger.ledger.LedgerRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

// Checks every account's balance against its postings and publishes the worst drift as a gauge.
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final LedgerRepository ledger;
    private final AtomicLong maxAbsDrift = new AtomicLong(0);
    private final AtomicLong worstTrialBalanceNet = new AtomicLong(0);

    private volatile ReconciliationReport lastReport;

    public ReconciliationService(LedgerRepository ledger, MeterRegistry meters) {
        this.ledger = ledger;
        meters.gauge("openledger.reconciliation.max_abs_drift", maxAbsDrift, AtomicLong::doubleValue);
        meters.gauge("openledger.reconciliation.trial_balance_worst_net", worstTrialBalanceNet, AtomicLong::doubleValue);
    }

    @Transactional(readOnly = true)
    public ReconciliationReport run() {
        List<LedgerRepository.BalanceDrift> drifts = ledger.findBalanceDrifts();
        List<LedgerRepository.CurrencyNet> trialBalance = ledger.trialBalance();

        long worstDrift = drifts.stream().mapToLong(LedgerRepository.BalanceDrift::absDrift).max().orElse(0L);
        long worstNet = trialBalance.stream().mapToLong(cn -> Math.abs(cn.net())).max().orElse(0L);
        maxAbsDrift.set(worstDrift);
        worstTrialBalanceNet.set(worstNet);

        boolean clean = worstDrift == 0L && worstNet == 0L;
        ReconciliationReport report = new ReconciliationReport(Instant.now(), clean, drifts, trialBalance);
        this.lastReport = report;

        if (clean) {
            log.info("reconciliation OK: {} accounts checked, trial balance flat", drifts.size());
        } else {
            log.error("RECONCILIATION DRIFT: worstAccountDrift={} worstTrialBalanceNet={} details={}",
                worstDrift, worstNet, report);
        }
        return report;
    }

    public ReconciliationReport lastReport() {
        return lastReport;
    }
}
