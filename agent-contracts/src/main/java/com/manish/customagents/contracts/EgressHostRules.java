package com.manish.customagents.contracts;

import java.util.Locale;

/**
 * Host-pattern rules for the per-tenant HTTP egress allowlist, shared so Management (which validates
 * at publish time) and Runtime (which enforces at execution time) cannot drift apart.
 *
 * <p>This type answers only "does this host match a pattern the tenant registered". Whether the host
 * is safe to reach at all - loopback, link-local, private ranges - is platform policy enforced in
 * Runtime after DNS resolution, and is deliberately not expressible here.
 */
public final class EgressHostRules {

    public static final int MAX_PATTERN_LENGTH = 255;
    private static final String WILDCARD_PREFIX = "*.";

    private EgressHostRules() {
    }

    /**
     * A pattern is either an exact host or a {@code *.suffix} wildcard. The wildcard must cover at
     * least two labels ({@code *.example.com}, never {@code *.com}), so one registration cannot open
     * a whole public suffix.
     */
    public static boolean isValidPattern(String pattern) {
        if (pattern == null) {
            return false;
        }
        String normalized = normalize(pattern);
        if (normalized.isEmpty() || normalized.length() > MAX_PATTERN_LENGTH) {
            return false;
        }
        if (normalized.startsWith(WILDCARD_PREFIX)) {
            String suffix = normalized.substring(WILDCARD_PREFIX.length());
            return suffix.indexOf('.') > 0 && isHostName(suffix);
        }
        return isHostName(normalized);
    }

    /** True when {@code host} is covered by {@code pattern}. Both are normalized first. */
    public static boolean matches(String pattern, String host) {
        if (pattern == null || host == null) {
            return false;
        }
        String normalizedPattern = normalize(pattern);
        String normalizedHost = normalize(host);
        if (normalizedPattern.isEmpty() || normalizedHost.isEmpty()) {
            return false;
        }
        if (normalizedPattern.startsWith(WILDCARD_PREFIX)) {
            // ".example.com" matches "api.example.com" but not "example.com" itself: registering a
            // wildcard should not silently grant the apex.
            String suffix = normalizedPattern.substring(1);
            return normalizedHost.endsWith(suffix) && normalizedHost.length() > suffix.length();
        }
        return normalizedHost.equals(normalizedPattern);
    }

    /** Lower-cases, trims, and drops a single trailing dot so "HOST." and "host" are one pattern. */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isHostName(String value) {
        if (value.isEmpty() || value.length() > MAX_PATTERN_LENGTH) {
            return false;
        }
        for (String label : value.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                    || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
            for (int index = 0; index < label.length(); index++) {
                char character = label.charAt(index);
                boolean allowed = (character >= 'a' && character <= 'z')
                        || (character >= '0' && character <= '9')
                        || character == '-';
                if (!allowed) {
                    return false;
                }
            }
        }
        return true;
    }
}
