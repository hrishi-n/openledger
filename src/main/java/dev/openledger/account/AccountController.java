package dev.openledger.account;

import dev.openledger.ledger.LedgerRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView create(@RequestBody @jakarta.validation.Valid CreateAccountRequest req) {
        Account a = service.create(req.type(), req.currency(), req.ownerRef(),
            req.minBalance() == null ? 0L : req.minBalance());
        return AccountView.of(a, 0L);
    }

    @GetMapping("/{id}")
    public AccountView get(@PathVariable UUID id) {
        return AccountView.of(service.get(id), service.balance(id));
    }

    @GetMapping("/{id}/balance")
    public BalanceView balance(@PathVariable UUID id, @RequestParam(required = false) Instant asOf) {
        long balance = asOf == null ? service.balance(id) : service.balanceAsOf(id, asOf);
        return new BalanceView(id, balance, asOf);
    }

    @GetMapping("/{id}/transactions")
    public List<LedgerRepository.StatementRow> transactions(@PathVariable UUID id,
                                                            @RequestParam(defaultValue = "50") int limit) {
        return service.statement(id, limit);
    }

    public record CreateAccountRequest(
        @NotNull AccountType type,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
        @NotBlank String ownerRef,
        Long minBalance
    ) {}

    public record AccountView(UUID id, AccountType type, String currency, String ownerRef,
                              long minBalance, long balance, Instant createdAt) {
        static AccountView of(Account a, long balance) {
            return new AccountView(a.id(), a.type(), a.currency(), a.ownerRef(), a.minBalance(), balance, a.createdAt());
        }
    }

    public record BalanceView(UUID accountId, long balance, Instant asOf) {}
}
