package dev.openledger.transfer;

import dev.openledger.account.Direction;
import dev.openledger.error.LedgerExceptions;
import dev.openledger.ledger.EntryView;
import dev.openledger.ledger.LedgerService;
import dev.openledger.ledger.PostEntryCommand;
import dev.openledger.ledger.PostingLine;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Turns a source-to-destination transfer, with an optional fee, into a balanced journal entry.
@Service
public class TransferService {

    private final LedgerService ledger;

    public TransferService(LedgerService ledger) {
        this.ledger = ledger;
    }

    public EntryView transfer(UUID sourceAccountId, UUID destinationAccountId, long amount, String currency,
                              Long feeAmount, UUID feeAccountId, String reference, String idempotencyKey,
                              Object idempotencyPayload) {
        long fee = feeAmount == null ? 0L : feeAmount;
        if (fee < 0L || fee >= amount) {
            throw new LedgerExceptions.UnbalancedEntry("fee must be >= 0 and less than the transfer amount");
        }
        if (fee > 0L && feeAccountId == null) {
            throw new LedgerExceptions.UnbalancedEntry("feeAccountId is required when feeAmount > 0");
        }

        String ccy = currency.toUpperCase();
        List<PostingLine> lines = new ArrayList<>(3);
        lines.add(new PostingLine(sourceAccountId, Direction.DEBIT, amount, ccy));
        lines.add(new PostingLine(destinationAccountId, Direction.CREDIT, amount - fee, ccy));
        if (fee > 0L) {
            lines.add(new PostingLine(feeAccountId, Direction.CREDIT, fee, ccy));
        }

        var cmd = new PostEntryCommand(
            reference,
            reference == null ? "Transfer" : "Transfer " + reference,
            "transfer-api",
            null,
            lines);
        return ledger.postEntry(cmd, idempotencyKey, idempotencyPayload);
    }
}
