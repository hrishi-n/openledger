package dev.openledger.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

// Reads the tenant id from the X-Tenant-Id header, or falls back to the default tenant.
@Component
public class TenantFilter extends OncePerRequestFilter {

    private final UUID defaultTenant;

    public TenantFilter(@Value("${openledger.default-tenant}") UUID defaultTenant) {
        this.defaultTenant = defaultTenant;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        try {
            String header = request.getHeader("X-Tenant-Id");
            TenantContext.set(header == null || header.isBlank() ? defaultTenant : UUID.fromString(header));
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
