package com.manish.customagents.runtime.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform-level policy for HTTP tools.
 *
 * <p>Which third-party hosts a tenant may reach is tenant data, held in the management database's
 * {@code tenant_egress_hosts} and administered per tenant. What remains here is only what no tenant
 * may decide for itself:
 *
 * <ul>
 *   <li>{@code allowedHosts} - hosts allowed for <em>every</em> tenant. Intended for local
 *       development ({@code host.docker.internal}); leave empty in a deployed environment.
 *   <li>{@code allowPrivateNetworks} - lets tools reach loopback, link-local, and private ranges.
 *       Off by default: on, a tenant that registers a host resolving to 169.254.169.254 can read
 *       cloud instance credentials.
 * </ul>
 */
@ConfigurationProperties("agents.tools.http")
public class DynamicHttpToolProperties {

    private List<String> allowedHosts = new ArrayList<>();
    private boolean allowPrivateNetworks = false;
    private Duration allowlistCacheTtl = Duration.ofSeconds(30);

    public List<String> getAllowedHosts() {
        return List.copyOf(allowedHosts);
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? new ArrayList<>() : new ArrayList<>(allowedHosts);
    }

    public boolean isAllowPrivateNetworks() {
        return allowPrivateNetworks;
    }

    public void setAllowPrivateNetworks(boolean allowPrivateNetworks) {
        this.allowPrivateNetworks = allowPrivateNetworks;
    }

    public Duration getAllowlistCacheTtl() {
        return allowlistCacheTtl;
    }

    public void setAllowlistCacheTtl(Duration allowlistCacheTtl) {
        this.allowlistCacheTtl = allowlistCacheTtl == null || allowlistCacheTtl.isNegative()
                ? Duration.ofSeconds(30)
                : allowlistCacheTtl;
    }
}
