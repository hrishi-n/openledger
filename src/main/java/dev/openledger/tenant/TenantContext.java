package dev.openledger.tenant;

import java.util.UUID;

// Holds the current request's tenant id in a thread-local.
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static UUID require() {
        UUID id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException("no tenant bound to the current request");
        }
        return id;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
