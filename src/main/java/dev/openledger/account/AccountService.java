package dev.openledger.account;

import dev.openledger.error.LedgerExceptions;
import dev.openledger.ledger.LedgerRepository;
import dev.openledger.tenant.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public AccountService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Transactional
    public Account create(AccountType type, String currency, String ownerRef, long minBalance) {
        Account account = new Account(
            UUID.randomUUID(), TenantContext.require(), type, currency.toUpperCase(), ownerRef, minBalance, Instant.now());
        try {
            accounts.insert(account);
        } catch (DuplicateKeyException e) {
            throw new LedgerExceptions.UnbalancedEntry(
                "an account for %s in %s already exists".formatted(ownerRef, currency.toUpperCase()));
        }
        return account;
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.find(TenantContext.require(), id)
            .orElseThrow(() -> new LedgerExceptions.NotFound("account " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public long balance(UUID id) {
        get(id);
        return ledger.currentBalance(id).orElse(0L);
    }

    @Transactional(readOnly = true)
    public long balanceAsOf(UUID id, Instant asOf) {
        get(id);
        return ledger.balanceAsOf(id, asOf);
    }

    @Transactional(readOnly = true)
    public List<LedgerRepository.StatementRow> statement(UUID id, int limit) {
        get(id);
        return ledger.statement(TenantContext.require(), id, Math.clamp(limit, 1, 500));
    }
}
