package com.manish.customagents.runtime.definition;

import com.manish.customagents.runtime.config.DynamicHttpToolProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * Reads a tenant's HTTP egress allowlist from the management database, the same connection Runtime
 * already uses for published definitions.
 *
 * <p>Cached for a short TTL because it is consulted on every HTTP tool call. The TTL is also the
 * revocation delay: removing a host takes effect for in-flight agents within one TTL, which is why
 * it is kept small rather than cached for the process lifetime.
 */
@Repository
public class TenantEgressAllowlistRepository {

    private static final String FIND_ACTIVE_SQL = """
            SELECT host_pattern
            FROM tenant_egress_hosts
            WHERE license_code = ? AND status = 'ACTIVE'
            """;

    private final ManagementDatabaseClient database;
    private final Duration cacheTtl;
    private final Map<String, CachedPatterns> cache = new ConcurrentHashMap<>();

    public TenantEgressAllowlistRepository(
            ManagementDatabaseClient database, DynamicHttpToolProperties properties) {
        this.database = database;
        this.cacheTtl = properties.getAllowlistCacheTtl();
    }

    public List<String> activePatterns(String licenseCode) {
        Instant now = Instant.now();
        CachedPatterns cached = cache.get(licenseCode);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.patterns();
        }
        List<String> patterns = database.jdbcTemplate().queryForList(
                FIND_ACTIVE_SQL, String.class, licenseCode);
        cache.put(licenseCode, new CachedPatterns(List.copyOf(patterns), now.plus(cacheTtl)));
        return patterns;
    }

    /** Drops the cache, so a test or an operator can force the next call to re-read. */
    public void invalidate() {
        cache.clear();
    }

    private record CachedPatterns(List<String> patterns, Instant expiresAt) {
    }
}
