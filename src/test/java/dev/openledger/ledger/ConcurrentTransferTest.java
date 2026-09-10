package dev.openledger.ledger;

import dev.openledger.account.Account;
import dev.openledger.account.AccountService;
import dev.openledger.account.AccountType;
import dev.openledger.account.Direction;
import dev.openledger.error.LedgerExceptions;
import dev.openledger.reconciliation.ReconciliationService;
import dev.openledger.support.AbstractIntegrationTest;
import dev.openledger.tenant.TenantContext;
import dev.openledger.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// Fires many concurrent transfers out of one wallet and checks for oversell or drift.
class ConcurrentTransferTest extends AbstractIntegrationTest {

    @Autowired AccountService accounts;
    @Autowired TransferService transfers;
    @Autowired LedgerService ledger;
    @Autowired ReconciliationService reconciliation;

    @Test
    void concurrent_transfers_never_oversell_the_source_wallet() throws Exception {
        int fundedTransfers = 150;
        int attempts = 250;
        long unit = 100L;

        Account opening = accounts.create(AccountType.EQUITY, "USD", "equity:opening", -1_000_000_000L);
        Account source = accounts.create(AccountType.LIABILITY, "USD", "user:source", 0L);
        Account sink = accounts.create(AccountType.LIABILITY, "USD", "user:sink", 0L);

        ledger.postEntry(new PostEntryCommand("seed", "Opening balance", "test", null, List.of(
            new PostingLine(opening.id(), Direction.DEBIT, fundedTransfers * unit, "USD"),
            new PostingLine(source.id(), Direction.CREDIT, fundedTransfers * unit, "USD"))), null, null);

        UUID currentTenant = tenant;
        var succeeded = new AtomicInteger();
        var rejected = new AtomicInteger();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(32);

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                TenantContext.set(currentTenant);
                try {
                    start.await();
                    transfers.transfer(source.id(), sink.id(), unit, "USD", null, null, null, null, null);
                    succeeded.incrementAndGet();
                } catch (LedgerExceptions.InsufficientFunds expected) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    TenantContext.clear();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(succeeded.get()).isEqualTo(fundedTransfers);
        assertThat(rejected.get()).isEqualTo(attempts - fundedTransfers);
        assertThat(accounts.balance(source.id())).isZero();
        assertThat(accounts.balance(sink.id())).isEqualTo(fundedTransfers * unit);
        assertThat(reconciliation.run().clean()).isTrue();
    }
}
