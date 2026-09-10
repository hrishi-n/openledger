package dev.openledger.ledger;

import dev.openledger.account.Account;
import dev.openledger.account.AccountService;
import dev.openledger.account.AccountType;
import dev.openledger.error.LedgerExceptions;
import dev.openledger.reconciliation.ReconciliationReport;
import dev.openledger.reconciliation.ReconciliationService;
import dev.openledger.support.AbstractIntegrationTest;
import dev.openledger.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferIntegrationTest extends AbstractIntegrationTest {

    @Autowired AccountService accounts;
    @Autowired TransferService transfers;
    @Autowired LedgerService ledger;
    @Autowired ReconciliationService reconciliation;

    @Test
    void transfer_with_fee_moves_money_and_keeps_the_ledger_balanced() {
        Account opening = accounts.create(AccountType.EQUITY, "USD", "equity:opening", -1_000_000_000L);
        Account walletA = accounts.create(AccountType.LIABILITY, "USD", "user:A", 0L);
        Account walletB = accounts.create(AccountType.LIABILITY, "USD", "user:B", 0L);
        Account fees = accounts.create(AccountType.REVENUE, "USD", "revenue:fees", -1_000_000_000L);

        // fund wallet A with $100.00
        ledger.postEntry(new PostEntryCommand("seed-A", "Opening balance", "test", null, java.util.List.of(
            new PostingLine(opening.id(), dev.openledger.account.Direction.DEBIT, 10_000L, "USD"),
            new PostingLine(walletA.id(), dev.openledger.account.Direction.CREDIT, 10_000L, "USD"))), null, null);

        // A sends $30.00 to B, $1.00 platform fee
        transfers.transfer(walletA.id(), walletB.id(), 3_100L, "USD", 100L, fees.id(), "t1", "idem-1", "idem-1");

        assertThat(accounts.balance(walletA.id())).isEqualTo(6_900L);
        assertThat(accounts.balance(walletB.id())).isEqualTo(3_000L);
        assertThat(accounts.balance(fees.id())).isEqualTo(100L);

        ReconciliationReport report = reconciliation.run();
        assertThat(report.clean()).isTrue();
    }

    @Test
    void transfer_beyond_balance_is_rejected() {
        Account opening = accounts.create(AccountType.EQUITY, "USD", "equity:opening", -1_000_000_000L);
        Account walletA = accounts.create(AccountType.LIABILITY, "USD", "user:A", 0L);
        Account walletB = accounts.create(AccountType.LIABILITY, "USD", "user:B", 0L);

        ledger.postEntry(new PostEntryCommand("seed-A", "Opening balance", "test", null, java.util.List.of(
            new PostingLine(opening.id(), dev.openledger.account.Direction.DEBIT, 5_000L, "USD"),
            new PostingLine(walletA.id(), dev.openledger.account.Direction.CREDIT, 5_000L, "USD"))), null, null);

        assertThatThrownBy(() ->
            transfers.transfer(walletA.id(), walletB.id(), 5_001L, "USD", null, null, "t2", null, null))
            .isInstanceOf(LedgerExceptions.InsufficientFunds.class);

        assertThat(accounts.balance(walletA.id())).isEqualTo(5_000L);
    }

    @Test
    void unbalanced_entry_is_rejected_before_it_is_written() {
        Account walletA = accounts.create(AccountType.LIABILITY, "USD", "user:A", -1_000_000_000L);
        Account walletB = accounts.create(AccountType.LIABILITY, "USD", "user:B", -1_000_000_000L);

        assertThatThrownBy(() -> ledger.postEntry(new PostEntryCommand("bad", "unbalanced", "test", null,
            java.util.List.of(
                new PostingLine(walletA.id(), dev.openledger.account.Direction.DEBIT, 100L, "USD"),
                new PostingLine(walletB.id(), dev.openledger.account.Direction.CREDIT, 90L, "USD"))), null, null))
            .isInstanceOf(LedgerExceptions.UnbalancedEntry.class);
    }

    @Test
    void same_idempotency_key_returns_the_same_entry() {
        Account opening = accounts.create(AccountType.EQUITY, "USD", "equity:opening", -1_000_000_000L);
        Account walletA = accounts.create(AccountType.LIABILITY, "USD", "user:A", 0L);
        Account walletB = accounts.create(AccountType.LIABILITY, "USD", "user:B", 0L);

        ledger.postEntry(new PostEntryCommand("seed-A", "Opening balance", "test", null, java.util.List.of(
            new PostingLine(opening.id(), dev.openledger.account.Direction.DEBIT, 5_000L, "USD"),
            new PostingLine(walletA.id(), dev.openledger.account.Direction.CREDIT, 5_000L, "USD"))), null, null);

        EntryView first = transfers.transfer(walletA.id(), walletB.id(), 1_000L, "USD", null, null, "t3", "k9", "k9");
        EntryView second = transfers.transfer(walletA.id(), walletB.id(), 1_000L, "USD", null, null, "t3", "k9", "k9");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(accounts.balance(walletA.id())).isEqualTo(4_000L); // charged once
    }
}
