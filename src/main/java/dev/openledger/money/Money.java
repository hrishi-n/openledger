package dev.openledger.money;

import java.util.Currency;
import java.util.Objects;

// An immutable monetary amount: a signed integer of minor units plus a currency code.
public record Money(long minorUnits, String currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        currency = currency.toUpperCase();
        Currency.getInstance(currency); // throws IllegalArgumentException for an unknown code
    }

    public static Money of(long minorUnits, String currency) {
        return new Money(minorUnits, currency);
    }

    public static Money zero(String currency) {
        return new Money(0L, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                "currency mismatch: %s vs %s".formatted(currency, other.currency));
        }
    }
}
