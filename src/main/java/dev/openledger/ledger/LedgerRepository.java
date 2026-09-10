package dev.openledger.ledger;

import dev.openledger.account.Direction;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LedgerRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public LedgerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Locks the balance rows for the given accounts in a fixed order and returns their current balances.
    public Map<UUID, Long> lockBalances(Collection<UUID> accountIds) {
        var sql = """
            SELECT account_id, balance
            FROM account_balances
            WHERE account_id IN (:ids)
            ORDER BY account_id
            FOR UPDATE
            """;
        Map<UUID, Long> out = new LinkedHashMap<>();
        RowCallbackHandler handler = rs -> out.put(rs.getObject("account_id", UUID.class), rs.getLong("balance"));
        jdbc.query(sql, new MapSqlParameterSource("ids", accountIds), handler);
        return out;
    }

    public void insertEntry(UUID id, UUID tenantId, PostEntryCommand cmd, Instant now) {
        var sql = """
            INSERT INTO journal_entries (id, tenant_id, external_id, description, reverses_entry, created_by, created_at)
            VALUES (:id, :tenantId, :externalId, :description, :reversesEntry, :createdBy, :createdAt)
            """;
        jdbc.update(sql, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("tenantId", tenantId)
            .addValue("externalId", cmd.externalId())
            .addValue("description", cmd.description())
            .addValue("reversesEntry", cmd.reversesEntry())
            .addValue("createdBy", cmd.createdBy())
            .addValue("createdAt", Timestamp.from(now)));
    }

    public void insertPostings(UUID entryId, List<PostingLine> lines, Instant now) {
        var sql = """
            INSERT INTO postings (id, entry_id, account_id, direction, amount, currency, created_at)
            VALUES (:id, :entryId, :accountId, :direction, :amount, :currency, :createdAt)
            """;
        SqlParameterSource[] batch = lines.stream()
            .map(l -> (SqlParameterSource) new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("entryId", entryId)
                .addValue("accountId", l.accountId())
                .addValue("direction", l.direction().name())
                .addValue("amount", l.amount())
                .addValue("currency", l.currency())
                .addValue("createdAt", Timestamp.from(now)))
            .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(sql, batch);
    }

    public void applyBalanceDelta(UUID accountId, long delta) {
        jdbc.update("""
            UPDATE account_balances
            SET balance = balance + :delta, version = version + 1, updated_at = now()
            WHERE account_id = :id
            """, new MapSqlParameterSource().addValue("delta", delta).addValue("id", accountId));
    }

    public void markReversed(UUID tenantId, UUID entryId) {
        jdbc.update("""
            UPDATE journal_entries SET status = 'REVERSED'
            WHERE tenant_id = :tenantId AND id = :id
            """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", entryId));
    }

    public Optional<Long> currentBalance(UUID accountId) {
        return jdbc.query("SELECT balance FROM account_balances WHERE account_id = :id",
                new MapSqlParameterSource("id", accountId),
                (rs, n) -> rs.getLong("balance"))
            .stream().findFirst();
    }

    /** Sums postings for an account up to a given instant. */
    public long balanceAsOf(UUID accountId, Instant asOf) {
        Long v = jdbc.queryForObject("""
            SELECT COALESCE(sum(p.amount * CASE
                WHEN (a.type IN ('ASSET','EXPENSE')            AND p.direction = 'DEBIT')
                  OR (a.type IN ('LIABILITY','EQUITY','REVENUE') AND p.direction = 'CREDIT')
                THEN 1 ELSE -1 END), 0)
            FROM postings p JOIN accounts a ON a.id = p.account_id
            WHERE p.account_id = :id AND p.created_at <= :asOf
            """, new MapSqlParameterSource().addValue("id", accountId).addValue("asOf", Timestamp.from(asOf)),
            Long.class);
        return v == null ? 0L : v;
    }

    private static final RowMapper<EntryView.Line> LINE_MAPPER = (rs, n) -> new EntryView.Line(
        rs.getObject("account_id", UUID.class),
        Direction.valueOf(rs.getString("direction")),
        rs.getLong("amount"),
        rs.getString("currency").trim());

    public Optional<EntryView> findEntry(UUID tenantId, UUID id) {
        var head = jdbc.query("""
            SELECT id, status, description, external_id, created_at
            FROM journal_entries WHERE tenant_id = :tenantId AND id = :id
            """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", id),
            (rs, n) -> new EntryView(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getString("description"),
                rs.getString("external_id"),
                rs.getTimestamp("created_at").toInstant(),
                List.of()));
        if (head.isEmpty()) {
            return Optional.empty();
        }
        var lines = jdbc.query(
            "SELECT account_id, direction, amount, currency FROM postings WHERE entry_id = :id ORDER BY created_at, id",
            new MapSqlParameterSource("id", id), LINE_MAPPER);
        EntryView e = head.get(0);
        return Optional.of(new EntryView(e.id(), e.status(), e.description(), e.externalId(), e.createdAt(), lines));
    }

    public List<StatementRow> statement(UUID tenantId, UUID accountId, int limit) {
        return jdbc.query("""
            SELECT p.entry_id, p.direction, p.amount, p.currency, p.created_at, e.description
            FROM postings p
            JOIN journal_entries e ON e.id = p.entry_id
            WHERE e.tenant_id = :tenantId AND p.account_id = :accountId
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT :limit
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("accountId", accountId)
                .addValue("limit", limit),
            (rs, n) -> new StatementRow(
                rs.getObject("entry_id", UUID.class),
                Direction.valueOf(rs.getString("direction")),
                rs.getLong("amount"),
                rs.getString("currency").trim(),
                rs.getString("description"),
                rs.getTimestamp("created_at").toInstant()));
    }

    // ---- reconciliation queries ----

    /** Finds accounts whose materialized balance disagrees with the sum of their postings. */
    public List<BalanceDrift> findBalanceDrifts() {
        return jdbc.query("""
            SELECT b.account_id, b.balance AS materialized, COALESCE(r.recomputed, 0) AS recomputed
            FROM account_balances b
            LEFT JOIN (
                SELECT p.account_id,
                       sum(p.amount * CASE
                           WHEN (a.type IN ('ASSET','EXPENSE')            AND p.direction = 'DEBIT')
                             OR (a.type IN ('LIABILITY','EQUITY','REVENUE') AND p.direction = 'CREDIT')
                           THEN 1 ELSE -1 END) AS recomputed
                FROM postings p JOIN accounts a ON a.id = p.account_id
                GROUP BY p.account_id
            ) r ON r.account_id = b.account_id
            WHERE b.balance <> COALESCE(r.recomputed, 0)
            """, new MapSqlParameterSource(),
            (rs, n) -> new BalanceDrift(
                rs.getObject("account_id", UUID.class),
                rs.getLong("materialized"),
                rs.getLong("recomputed")));
    }

    /** Sums postings per currency across the whole ledger. */
    public List<CurrencyNet> trialBalance() {
        return jdbc.query("""
            SELECT currency, sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) AS net
            FROM postings GROUP BY currency ORDER BY currency
            """, new MapSqlParameterSource(),
            (rs, n) -> new CurrencyNet(rs.getString("currency").trim(), rs.getLong("net")));
    }

    public record StatementRow(UUID entryId, Direction direction, long amount, String currency,
                               String description, Instant at) {}

    public record BalanceDrift(UUID accountId, long materialized, long recomputed) {
        public long absDrift() {
            return Math.abs(materialized - recomputed);
        }
    }

    public record CurrencyNet(String currency, long net) {}
}
