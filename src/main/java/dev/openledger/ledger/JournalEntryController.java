package dev.openledger.ledger;

import dev.openledger.account.Direction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/journal-entries")
public class JournalEntryController {

    private final LedgerService ledger;

    public JournalEntryController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EntryView post(@RequestBody @Valid JournalEntryRequest req,
                          @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        var cmd = new PostEntryCommand(
            req.externalId(), req.description(), req.createdBy(), null,
            req.postings().stream()
                .map(l -> new PostingLine(l.accountId(), l.direction(), l.amount(), l.currency()))
                .toList());
        return ledger.postEntry(cmd, idempotencyKey, req);
    }

    @GetMapping("/{id}")
    public EntryView get(@PathVariable UUID id) {
        return ledger.getEntry(id);
    }

    @PostMapping("/{id}/reversal")
    @ResponseStatus(HttpStatus.CREATED)
    public EntryView reverse(@PathVariable UUID id,
                             @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ledger.reverse(id, idempotencyKey);
    }

    public record JournalEntryRequest(
        String externalId,
        @NotBlank String description,
        String createdBy,
        @NotEmpty @Size(min = 2) @Valid List<Line> postings
    ) {}

    public record Line(
        @NotNull UUID accountId,
        @NotNull Direction direction,
        @Positive long amount,
        @NotBlank String currency
    ) {}
}
