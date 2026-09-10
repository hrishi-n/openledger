package dev.openledger.account;

// The five classical account types, each with a fixed normal side.
public enum AccountType {

    ASSET(NormalSide.DEBIT),
    LIABILITY(NormalSide.CREDIT),
    EQUITY(NormalSide.CREDIT),
    REVENUE(NormalSide.CREDIT),
    EXPENSE(NormalSide.DEBIT);

    public enum NormalSide { DEBIT, CREDIT }

    private final NormalSide normalSide;

    AccountType(NormalSide normalSide) {
        this.normalSide = normalSide;
    }

    public NormalSide normalSide() {
        return normalSide;
    }
}
