package dev.openledger.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openledger.account.Account;
import dev.openledger.account.AccountRepository;
import dev.openledger.account.Direction;
import dev.openledger.error.LedgerExceptions;
import dev.openledger.idempotency.IdempotencyRepository;
import dev.openledger.idempotency.RequestHasher;
import dev.openledger.tenant.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// Posts and reverses journal entries, checking balance and idempotency rules along the way.
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final IdempotencyRepository idempotency;
    private final RequestHasher hasher;
    private final ObjectMapper objectMapper;

    public LedgerService(AccountRepository accounts, LedgerRepository ledger,
                         IdempotencyRepository idempotency, RequestHasher hasher, ObjectMapper objectMapper) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public EntryView postEntry(PostEntryCommand cmd, String idempotencyKey, Object idempotencyPayload) {
        UUID tenantId = TenantContext.require();
        String fingerprint = idempotencyKey == null ? null : hasher.fingerprint(idempotencyPayload);

        if (idempotencyKey != null) {
            var replay = replay(tenantId, idempotencyKey, fingerprint);
            if (replay != null) {
                return replay;
            }
        }

        validateStructure(cmd);

        var accountIds = cmd.lines().stream().map(PostingLine::accountId).distinct().sorted().toList();
        var found = accounts.findAll(tenantId, accountIds);
        if (found.size() != accountIds.size()) {
            throw new LedgerExceptions.NotFound("one or more accounts do not exist in this tenant");
        }
        Map<UUID, Account> byId = found.stream().collect(Collectors.toMap(Account::id, a -> a));

        for (PostingLine line : cmd.lines()) {
            Account acc = byId.get(line.accountId());
            if (!acc.currency().equals(line.currency())) {
                throw new LedgerExceptions.CurrencyMismatch(acc.id(), acc.currency(), line.currency());
            }
        }

        // Locks balance rows in a fixed order so concurrent entries can't deadlock.
        Map<UUID, Long> locked = ledger.lockBalances(accountIds);

        Map<UUID, Long> delta = new LinkedHashMap<>();
        for (PostingLine line : cmd.lines()) {
            Account acc = byId.get(line.accountId());
            long signed = line.direction().signedEffect(acc.type().normalSide(), line.amount());
            delta.merge(acc.id(), signed, Long::sum);
        }
        for (Map.Entry<UUID, Long> e : delta.entrySet()) {
            Account acc = byId.get(e.getKey());
            long current = locked.getOrDefault(e.getKey(), 0L);
            if (current + e.getValue() < acc.minBalance()) {
                throw new LedgerExceptions.InsufficientFunds(acc.id(), current, e.getValue(), acc.minBalance());
            }
        }

        Instant now = Instant.now();
        UUID entryId = UUID.randomUUID();
        ledger.insertEntry(entryId, tenantId, cmd, now);
        ledger.insertPostings(entryId, cmd.lines(), now);
        delta.forEach(ledger::applyBalanceDelta);

        EntryView view = new EntryView(entryId, "POSTED", cmd.description(), cmd.externalId(), now,
            cmd.lines().stream()
                .map(l -> new EntryView.Line(l.accountId(), l.direction(), l.amount(), l.currency()))
                .toList());

        if (idempotencyKey != null) {
            try {
                idempotency.save(tenantId, idempotencyKey, fingerprint, 201, serialize(view), entryId);
            } catch (DuplicateKeyException raced) {
                // Falls back to the stored result of the duplicate that won the insert.
                EntryView replay = replay(tenantId, idempotencyKey, fingerprint);
                if (replay != null) {
                    return replay;
                }
                throw raced;
            }
        }
        return view;
    }

    @Transactional
    public EntryView reverse(UUID originalEntryId, String idempotencyKey) {
        UUID tenantId = TenantContext.require();
        EntryView original = ledger.findEntry(tenantId, originalEntryId)
            .orElseThrow(() -> new LedgerExceptions.NotFound("entry " + originalEntryId + " not found"));
        if ("REVERSED".equals(original.status())) {
            throw new LedgerExceptions.UnbalancedEntry("entry " + originalEntryId + " is already reversed");
        }

        List<PostingLine> reversedLines = original.postings().stream()
            .map(l -> new PostingLine(l.accountId(), l.direction().opposite(), l.amount(), l.currency()))
            .toList();
        var cmd = new PostEntryCommand(
            "reversal-of-" + originalEntryId,
            "Reversal of " + originalEntryId,
            "system",
            originalEntryId,
            reversedLines);

        EntryView reversal = postEntry(cmd, idempotencyKey, cmd);
        ledger.markReversed(tenantId, originalEntryId);
        return reversal;
    }

    @Transactional(readOnly = true)
    public EntryView getEntry(UUID id) {
        return ledger.findEntry(TenantContext.require(), id)
            .orElseThrow(() -> new LedgerExceptions.NotFound("entry " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<LedgerRepository.StatementRow> statement(UUID accountId, int limit) {
        return ledger.statement(TenantContext.require(), accountId, Math.min(Math.max(limit, 1), 500));
    }

    // Checks structural and zero-sum rules before any row is touched.
    void validateStructure(PostEntryCommand cmd) {
        if (cmd.lines().size() < 2) {
            throw new LedgerExceptions.UnbalancedEntry("an entry needs at least two postings");
        }
        for (PostingLine l : cmd.lines()) {
            if (l.amount() <= 0L) {
                throw new LedgerExceptions.UnbalancedEntry("posting amounts must be positive");
            }
            if (l.currency() == null || l.currency().isBlank()) {
                throw new LedgerExceptions.UnbalancedEntry("posting currency is required");
            }
        }
        Map<String, Long> netByCurrency = new HashMap<>();
        for (PostingLine l : cmd.lines()) {
            netByCurrency.merge(l.currency(), l.balanceContribution(), Long::sum);
        }
        netByCurrency.forEach((ccy, net) -> {
            if (net != 0L) {
                throw new LedgerExceptions.UnbalancedEntry(
                    "entry does not net to zero in %s: net = %d".formatted(ccy, net));
            }
        });
    }

    private EntryView replay(UUID tenantId, String idempotencyKey, String fingerprint) {
        return idempotency.find(tenantId, idempotencyKey)
            .map(stored -> {
                if (!stored.requestHash().equals(fingerprint)) {
                    throw new LedgerExceptions.IdempotencyConflict(idempotencyKey);
                }
                return deserialize(stored.responseBody());
            })
            .orElse(null);
    }

    private String serialize(EntryView view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private EntryView deserialize(String json) {
        try {
            return objectMapper.readValue(json, EntryView.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
