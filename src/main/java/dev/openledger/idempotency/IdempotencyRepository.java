package dev.openledger.idempotency;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class IdempotencyRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public IdempotencyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Stored> find(UUID tenantId, String key) {
        return jdbc.query("""
            SELECT request_hash, response_status, response_body::text AS response_body
            FROM idempotency_keys
            WHERE tenant_id = :tenantId AND key = :key
            """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("key", key),
            (rs, n) -> new Stored(
                rs.getString("request_hash"),
                rs.getInt("response_status"),
                rs.getString("response_body")))
            .stream().findFirst();
    }

    public void save(UUID tenantId, String key, String requestHash, int status, String bodyJson, UUID entryId) {
        jdbc.update("""
            INSERT INTO idempotency_keys (tenant_id, key, request_hash, response_status, response_body, entry_id)
            VALUES (:tenantId, :key, :hash, :status, CAST(:body AS jsonb), :entryId)
            """, new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("key", key)
            .addValue("hash", requestHash)
            .addValue("status", status)
            .addValue("body", bodyJson)
            .addValue("entryId", entryId));
    }

    public record Stored(String requestHash, int responseStatus, String responseBody) {}
}
