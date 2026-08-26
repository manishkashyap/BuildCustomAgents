package com.manish.customagents.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * The bounds a human-interaction policy must satisfy.
 *
 * <p>Tool approvals and agent clarifications share their audience shape and expiry window. Held
 * here so the two cannot drift into accepting different things and reporting the difference as an
 * unexplained validation error on one endpoint but not the other.
 */
public final class HumanInteractionPolicyRules {

    public static final long MIN_EXPIRY_SECONDS = 60;
    public static final long MAX_EXPIRY_SECONDS = 604_800;
    public static final long DEFAULT_EXPIRY_SECONDS = 86_400;

    private static final Set<String> AUDIENCE_TYPES = Set.of("RUN_REQUESTER", "ROLE", "GROUP");
    private static final Set<String> AUDIENCE_TYPES_REQUIRING_VALUES = Set.of("ROLE", "GROUP");

    private HumanInteractionPolicyRules() {
    }

    public static boolean isExpiryInRange(JsonNode policy, String field) {
        long expiry = policy.path(field).asLong(DEFAULT_EXPIRY_SECONDS);
        return expiry >= MIN_EXPIRY_SECONDS && expiry <= MAX_EXPIRY_SECONDS;
    }

    /**
     * A valid audience is absent, or an object whose type is known and which carries values when
     * the type needs them. ROLE and GROUP are meaningless without at least one value.
     */
    public static boolean isAudienceValid(JsonNode audience) {
        if (audience.isMissingNode()) return true;
        if (!audience.isObject()) return false;
        String type = audience.path("type").asText("");
        if (!AUDIENCE_TYPES.contains(type)) return false;
        JsonNode values = audience.path("values");
        if (AUDIENCE_TYPES_REQUIRING_VALUES.contains(type)) {
            return values.isArray() && !values.isEmpty();
        }
        return values.isMissingNode() || values.isArray();
    }

    public static boolean isKnownAudienceType(String type) {
        return AUDIENCE_TYPES.contains(type);
    }
}
