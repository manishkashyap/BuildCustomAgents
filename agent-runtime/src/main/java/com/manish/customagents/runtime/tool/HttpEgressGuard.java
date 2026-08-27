package com.manish.customagents.runtime.tool;

import com.manish.customagents.contracts.EgressHostRules;
import com.manish.customagents.runtime.config.DynamicHttpToolProperties;
import com.manish.customagents.runtime.definition.TenantEgressAllowlistRepository;
import com.manish.customagents.runtime.errors.AgentExecutionException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Decides whether an HTTP tool may reach a URL. Two independent gates, in order:
 *
 * <ol>
 *   <li><b>Tenant allowlist</b> - is this host one the tenant registered? Tenant-configurable.
 *   <li><b>Network policy</b> - does the host resolve into a range no tool may reach? Platform-owned
 *       and deliberately not tenant-configurable.
 * </ol>
 *
 * <p>The second gate is what keeps the first one safe to delegate. Allowlisting by name alone would
 * let a tenant register a host they control that resolves to 169.254.169.254 and read cloud instance
 * credentials, so the resolved addresses are checked too - name-based approval, address-based denial.
 */
@Component
public class HttpEgressGuard {

    private final TenantEgressAllowlistRepository allowlist;
    private final DynamicHttpToolProperties properties;

    public HttpEgressGuard(
            TenantEgressAllowlistRepository allowlist, DynamicHttpToolProperties properties) {
        this.allowlist = allowlist;
        this.properties = properties;
    }

    public void check(URI uri, String licenseCode) {
        String host = EgressHostRules.normalize(uri.getHost());
        if (host.isEmpty()) {
            throw new AgentExecutionException("HTTP tool URL has no host");
        }
        requireAllowedHost(host, licenseCode);
        requireRoutableAddress(host);
    }

    private void requireAllowedHost(String host, String licenseCode) {
        // The platform list is a development convenience and is empty in a deployed environment;
        // the tenant's own registrations are the real source.
        boolean allowed = Stream.concat(
                        properties.getAllowedHosts().stream(),
                        allowlist.activePatterns(licenseCode).stream())
                .anyMatch(pattern -> EgressHostRules.matches(pattern, host));
        if (!allowed) {
            throw new AgentExecutionException(
                    "HTTP tool host is not on this tenant's egress allowlist: " + host);
        }
    }

    /**
     * Resolves the host and refuses any address a tool has no business reaching. Note this is a
     * check, not a pin: a name that resolves differently between here and the connection could still
     * slip through (DNS rebinding). Pinning the connection to the resolved address would close that,
     * and is the natural next hardening step.
     */
    private void requireRoutableAddress(String host) {
        if (properties.isAllowPrivateNetworks()) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            throw new AgentExecutionException("HTTP tool host cannot be resolved: " + host, exception);
        }
        for (InetAddress address : addresses) {
            String reason = blockedReason(address);
            if (reason != null) {
                // The address is deliberately not echoed back: it would confirm internal topology to
                // whoever controls the tool definition.
                throw new AgentExecutionException(
                        "HTTP tool host " + host + " resolves to a blocked network (" + reason + ")");
            }
        }
    }

    private static String blockedReason(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return "loopback";
        }
        if (address.isAnyLocalAddress()) {
            return "unspecified";
        }
        if (address.isLinkLocalAddress()) {
            // 169.254.0.0/16 - cloud instance metadata lives here.
            return "link-local";
        }
        if (address.isSiteLocalAddress()) {
            // 10/8, 172.16/12, 192.168/16, and the IPv6 equivalents.
            return "private";
        }
        if (address.isMulticastAddress()) {
            return "multicast";
        }
        byte[] octets = address.getAddress();
        if (octets.length == 4) {
            int first = octets[0] & 0xFF;
            int second = octets[1] & 0xFF;
            if (first == 100 && second >= 64 && second <= 127) {
                return "carrier-grade NAT";
            }
            if (first == 192 && second == 0 && (octets[2] & 0xFF) == 0) {
                return "IETF protocol assignment";
            }
            if (first == 0) {
                return "this-network";
            }
        }
        return null;
    }

    /** Exposed so callers can report what a tenant currently has registered. */
    public List<String> activePatterns(String licenseCode) {
        return allowlist.activePatterns(licenseCode);
    }
}
