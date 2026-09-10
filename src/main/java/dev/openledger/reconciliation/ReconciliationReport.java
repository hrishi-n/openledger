package dev.openledger.reconciliation;

import dev.openledger.ledger.LedgerRepository;

import java.time.Instant;
import java.util.List;

public record ReconciliationReport(
    Instant ranAt,
    boolean clean,
    List<LedgerRepository.BalanceDrift> balanceDrifts,
    List<LedgerRepository.CurrencyNet> trialBalance
) {}
