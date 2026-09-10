package dev.openledger.account;

import java.time.Instant;
import java.util.UUID;

public record Account(
    UUID id,
    UUID tenantId,
    AccountType type,
    String currency,
    String ownerRef,
    long minBalance,
    Instant createdAt
) {}
