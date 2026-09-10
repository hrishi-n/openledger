package dev.openledger.account;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {

    private static final RowMapper<Account> MAPPER = (rs, n) -> new Account(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        AccountType.valueOf(rs.getString("type")),
        rs.getString("currency").trim(),
        rs.getString("owner_ref"),
        rs.getLong("min_balance"),
        rs.getTimestamp("created_at").toInstant()
    );

    private final NamedParameterJdbcTemplate jdbc;

    public AccountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Account a) {
        var sql = """
            INSERT INTO accounts (id, tenant_id, type, currency, owner_ref, min_balance, created_at)
            VALUES (:id, :tenantId, :type, :currency, :ownerRef, :minBalance, :createdAt)
            """;
        jdbc.update(sql, new MapSqlParameterSource()
            .addValue("id", a.id())
            .addValue("tenantId", a.tenantId())
            .addValue("type", a.type().name())
            .addValue("currency", a.currency())
            .addValue("ownerRef", a.ownerRef())
            .addValue("minBalance", a.minBalance())
            .addValue("createdAt", java.sql.Timestamp.from(a.createdAt())));

        jdbc.update("INSERT INTO account_balances (account_id) VALUES (:id)",
            new MapSqlParameterSource("id", a.id()));
    }

    public Optional<Account> find(UUID tenantId, UUID id) {
        var rows = jdbc.query(
            "SELECT * FROM accounts WHERE tenant_id = :tenantId AND id = :id",
            new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", id),
            MAPPER);
        return rows.stream().findFirst();
    }

    public List<Account> findAll(UUID tenantId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
            "SELECT * FROM accounts WHERE tenant_id = :tenantId AND id IN (:ids)",
            new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("ids", ids),
            MAPPER);
    }
}
