package com.manish.customagents.egress.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.contracts.EgressHostRules;
import com.manish.customagents.contracts.ToolType;
import com.manish.customagents.error.ToolHostNotAllowedException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Publish-time gate: an HTTP tool may only be published if its host is on the tenant's egress
 * allowlist. Runtime checks again at execution time, because a host can be revoked after publish and
 * because the URL is expanded with model-supplied arguments before it is called.
 */
@Component
public class ToolEgressValidator {

    private final TenantEgressHostService egressHosts;

    public ToolEgressValidator(TenantEgressHostService egressHosts) {
        this.egressHosts = egressHosts;
    }

    public void check(String licenseCode, ToolType type, JsonNode configuration) {
        if (type != ToolType.HTTP || configuration == null || !configuration.isObject()) {
            return;
        }
        String urlTemplate = configuration.path("url").asText("");
        if (ToolUrlHost.hasTemplatedHost(urlTemplate)) {
            throw new ToolHostNotAllowedException(
                    ToolUrlHost.parse(urlTemplate),
                    "configuration.url must name a literal host; a templated host would let tool "
                            + "arguments choose the destination at run time");
        }
        String host = ToolUrlHost.parse(urlTemplate);
        if (host == null) {
            throw new ToolHostNotAllowedException(
                    null, "configuration.url does not contain a resolvable host");
        }
        List<String> patterns = egressHosts.activePatterns(licenseCode);
        boolean allowed = patterns.stream().anyMatch(pattern -> EgressHostRules.matches(pattern, host));
        if (!allowed) {
            throw new ToolHostNotAllowedException(host,
                    "Host " + host + " is not on this tenant's egress allowlist. An administrator "
                            + "must allow it under /api/v1/egress-hosts before the tool can be published.");
        }
    }
}
