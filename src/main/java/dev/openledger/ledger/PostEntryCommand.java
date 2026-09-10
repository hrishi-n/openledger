package dev.openledger.ledger;

import java.util.List;
import java.util.UUID;

public record PostEntryCommand(
    String externalId,
    String description,
    String createdBy,
    UUID reversesEntry,
    List<PostingLine> lines
) {
    public PostEntryCommand {
        createdBy = (createdBy == null || createdBy.isBlank()) ? "system" : createdBy;
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
