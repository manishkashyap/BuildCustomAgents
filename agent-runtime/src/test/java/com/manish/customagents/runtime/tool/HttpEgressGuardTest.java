package com.manish.customagents.runtime.tool;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.manish.customagents.runtime.config.DynamicHttpToolProperties;
import com.manish.customagents.runtime.definition.TenantEgressAllowlistRepository;
import com.manish.customagents.runtime.errors.AgentExecutionException;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HttpEgressGuardTest {

    @Mock
    private TenantEgressAllowlistRepository allowlist;

    private HttpEgressGuard guard(List<String> tenantPatterns, boolean allowPrivate,
            List<String> platformPatterns) {
        DynamicHttpToolProperties properties = new DynamicHttpToolProperties();
        properties.setAllowPrivateNetworks(allowPrivate);
        properties.setAllowedHosts(platformPatterns);
        when(allowlist.activePatterns("tenant-1")).thenReturn(tenantPatterns);
        return new HttpEgressGuard(allowlist, properties);
    }

    @Test
    void allowsAHostTheTenantRegistered() {
        assertThatCode(() -> guard(List.of("api.example.com"), true, List.of())
                .check(URI.create("https://api.example.com/x"), "tenant-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsASubdomainCoveredByAWildcard() {
        assertThatCode(() -> guard(List.of("*.example.com"), true, List.of())
                .check(URI.create("https://api.example.com/x"), "tenant-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void doesNotLetAWildcardGrantItsOwnApex() {
        assertThatThrownBy(() -> guard(List.of("*.example.com"), true, List.of())
                .check(URI.create("https://example.com/x"), "tenant-1"))
                .isInstanceOf(AgentExecutionException.class);
    }

    /**
     * The gate that must survive a tenant registering whatever it likes: an allowlisted name is
     * still refused when it resolves somewhere a tool has no business reaching.
     */
    @Test
    void refusesAnAllowlistedHostThatResolvesToLoopback() {
        assertThatThrownBy(() -> guard(List.of("localhost"), false, List.of())
                .check(URI.create("http://localhost/x"), "tenant-1"))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void refusesAnAllowlistedLiteralLinkLocalMetadataAddress() {
        assertThatThrownBy(() -> guard(List.of("169.254.169.254"), false, List.of())
                .check(URI.create("http://169.254.169.254/latest/meta-data/"), "tenant-1"))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("link-local");
    }

    @Test
    void refusesAnAllowlistedPrivateAddress() {
        assertThatThrownBy(() -> guard(List.of("10.0.0.5"), false, List.of())
                .check(URI.create("http://10.0.0.5/internal"), "tenant-1"))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("private");
    }

    @Test
    void allowsPrivateAddressesOnlyWhenTheOperatorOptsIn() {
        assertThatCode(() -> guard(List.of("10.0.0.5"), true, List.of())
                .check(URI.create("http://10.0.0.5/internal"), "tenant-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAPlatformWideHostWithoutATenantRegistration() {
        assertThatCode(() -> guard(List.of(), true, List.of("api.example.com"))
                .check(URI.create("https://api.example.com/x"), "tenant-1"))
                .doesNotThrowAnyException();
    }
}
