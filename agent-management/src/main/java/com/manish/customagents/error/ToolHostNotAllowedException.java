package com.manish.customagents.error;

/**
 * A tool cannot be published because its URL host is not on the tenant's egress allowlist. Raised at
 * publish time so the failure surfaces while the tool is being authored, rather than mid-run.
 */
public class ToolHostNotAllowedException extends RuntimeException {

    private final String host;

    public ToolHostNotAllowedException(String host, String detail) {
        super(detail);
        this.host = host;
    }

    public String host() {
        return host;
    }
}
