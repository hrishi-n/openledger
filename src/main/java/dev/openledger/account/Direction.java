package dev.openledger.account;

public enum Direction {

    DEBIT, CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }

    // Returns the signed effect of this posting on a normalized balance.
    public long signedEffect(AccountType.NormalSide normalSide, long amount) {
        boolean increases = switch (normalSide) {
            case DEBIT -> this == DEBIT;
            case CREDIT -> this == CREDIT;
        };
        return increases ? amount : -amount;
    }
}
