package dev.openledger.error;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/** Concrete domain errors, grouped for brevity. */
public final class LedgerExceptions {

    private LedgerExceptions() {}

    public static final class NotFound extends LedgerException {
        public NotFound(String message) {
            super(message);
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.NOT_FOUND;
        }

        @Override
        public String code() {
            return "not_found";
        }
    }

    public static final class UnbalancedEntry extends LedgerException {
        public UnbalancedEntry(String message) {
            super(message);
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }

        @Override
        public String code() {
            return "unbalanced_entry";
        }
    }

    public static final class CurrencyMismatch extends LedgerException {
        public CurrencyMismatch(UUID accountId, String accountCurrency, String postingCurrency) {
            super("account %s is %s but posting is %s".formatted(accountId, accountCurrency, postingCurrency));
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }

        @Override
        public String code() {
            return "currency_mismatch";
        }
    }

    public static final class InsufficientFunds extends LedgerException {
        public InsufficientFunds(UUID accountId, long current, long delta, long floor) {
            super("account %s balance %d + (%d) would breach floor %d".formatted(accountId, current, delta, floor));
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }

        @Override
        public String code() {
            return "insufficient_funds";
        }
    }

    public static final class IdempotencyConflict extends LedgerException {
        public IdempotencyConflict(String key) {
            super("idempotency key '%s' was already used with a different request body".formatted(key));
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.CONFLICT;
        }

        @Override
        public String code() {
            return "idempotency_conflict";
        }
    }
}
