package dev.openledger.ledger;

import dev.openledger.account.Direction;

import java.util.UUID;

/** One line of a journal entry, as supplied by a caller. Amount is always positive. */
public record PostingLine(UUID accountId, Direction direction, long amount, String currency) {

    public PostingLine {
        currency = currency == null ? null : currency.toUpperCase();
    }

    /** Signed contribution to the zero-sum check: debits positive, credits negative. */
    long balanceContribution() {
        return direction == Direction.DEBIT ? amount : -amount;
    }
}
