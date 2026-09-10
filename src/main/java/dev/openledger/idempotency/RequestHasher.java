package dev.openledger.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// Produces a stable SHA-256 fingerprint of a request payload.
@Component
public class RequestHasher {

    private final ObjectMapper objectMapper;

    public RequestHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String fingerprint(Object payload) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(payload);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("payload is not serializable", e);
        }
    }
}
