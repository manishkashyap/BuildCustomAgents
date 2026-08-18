package com.manish.customagents.runtime.api;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

record RuntimeActorContext(String actorId, Set<String> roles) {
    RuntimeActorContext requireAnyRole(String... required) {
        for (String role : required) {
            if (roles.contains(role)) return this;
        }
        throw new AccessDeniedException("Authenticated user lacks a required runtime role");
    }

    static RuntimeActorContext resolve(
            Authentication authentication, String fallbackActor, Set<String> fallbackRoles,
            String licenseCode) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return new RuntimeActorContext(fallbackActor, Set.copyOf(fallbackRoles));
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
        return new RuntimeActorContext(authentication.getName(), roles);
    }
}
