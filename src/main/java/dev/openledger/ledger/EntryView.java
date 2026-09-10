package dev.openledger.ledger;

import dev.openledger.account.Direction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EntryView(
    UUID id,
    String status,
    String description,
    String externalId,
    Instant createdAt,
    List<Line> postings
) {
    public record Line(UUID accountId, Direction direction, long amount, String currency) {}
}
