package dev.openledger.transfer;

import dev.openledger.ledger.EntryView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EntryView transfer(@RequestBody @Valid TransferRequest req,
                              @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return transfers.transfer(
            req.sourceAccountId(), req.destinationAccountId(), req.amount(), req.currency(),
            req.feeAmount(), req.feeAccountId(), req.reference(), idempotencyKey, req);
    }

    public record TransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID destinationAccountId,
        @Positive long amount,
        @NotBlank String currency,
        @PositiveOrZero Long feeAmount,
        UUID feeAccountId,
        String reference
    ) {}
}
