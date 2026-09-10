package dev.openledger.ledger;

import dev.openledger.account.Direction;
import dev.openledger.error.LedgerExceptions;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Checks the zero-sum rule against random entries, no database involved.
class DoubleEntryPropertyTest {

    private final LedgerService validator = new LedgerService(null, null, null, null, null);

    @Property(tries = 300)
    void any_entry_that_nets_to_zero_passes_validation(
        @ForAll @Size(min = 1, max = 8) List<@IntRange(min = 1, max = 1_000_000) Integer> amounts) {

        List<PostingLine> lines = new ArrayList<>();
        long total = 0;
        for (int amount : amounts) {
            lines.add(new PostingLine(UUID.randomUUID(), Direction.DEBIT, amount, "USD"));
            total += amount;
        }
        lines.add(new PostingLine(UUID.randomUUID(), Direction.CREDIT, total, "USD"));

        validator.validateStructure(new PostEntryCommand("p", "prop", "test", null, lines));
        assertThat(lines).hasSize(amounts.size() + 1);
    }

    @Property(tries = 300)
    void perturbing_a_single_amount_always_breaks_the_balance(
        @ForAll @IntRange(min = 1, max = 1_000_000) int amount,
        @ForAll @IntRange(min = 1, max = 999) int delta) {

        List<PostingLine> lines = List.of(
            new PostingLine(UUID.randomUUID(), Direction.DEBIT, amount + delta, "USD"),
            new PostingLine(UUID.randomUUID(), Direction.CREDIT, amount, "USD"));

        assertThatThrownBy(() ->
            validator.validateStructure(new PostEntryCommand("p", "prop", "test", null, lines)))
            .isInstanceOf(LedgerExceptions.UnbalancedEntry.class);
    }
}
