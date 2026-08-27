package com.manish.customagents.agent.api;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public record ManagementActorContext(String actorId, Set<String> roles) {
    public ManagementActorContext requireAnyRole(String... required) {
        if (Arrays.stream(required).anyMatch(roles::contains)) return this;
        throw new AccessDeniedException("Authenticated user lacks a required agent-management role");
    }

    public static ManagementActorContext resolve(Authentication authentication, String fallbackActor,
            String roleHeader, String licenseCode) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            Set<String> roles = Arrays.stream(roleHeader.split(","))
                    .map(String::strip).filter(value -> !value.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
            return new ManagementActorContext(fallbackActor.strip(), roles);
        }
        if (authentication instanceof JwtAuthenticationToken jwt) {
            Object licenses = jwt.getToken().getClaims().get("license_codes");
            if (!(licenses instanceof Collection<?> allowed)
                    || allowed.stream().map(Object::toString).noneMatch(licenseCode::equals)) {
                throw new AccessDeniedException("Authenticated user cannot access this license code");
            }
        }
        Set<String> roles = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                .collect(Collectors.toUnmodifiableSet());
        return new ManagementActorContext(authentication.getName(), roles);
    }
}
