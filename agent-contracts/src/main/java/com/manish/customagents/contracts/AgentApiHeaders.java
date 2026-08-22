package com.manish.customagents.contracts;

/** The request headers both services read. Declared once so the literals cannot drift. */
public final class AgentApiHeaders {

    public static final String LICENSE_CODE = "X-Agent-License-Code";
    public static final String USER_ID = "X-Agent-User-Id";
    public static final String ROLES = "X-Agent-Roles";
    public static final String CHANGE_REASON = "X-Agent-Change-Reason";
    public static final String INTERNAL_TOKEN = "X-Agent-Internal-Token";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private AgentApiHeaders() {
    }
}
