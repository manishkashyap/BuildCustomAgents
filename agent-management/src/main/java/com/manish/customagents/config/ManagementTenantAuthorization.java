package com.manish.customagents.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.manish.customagents.contracts.AgentApiHeaders;

@Component
public class ManagementTenantAuthorization implements HandlerInterceptor, WebMvcConfigurer {
    private final boolean enabled;

    public ManagementTenantAuthorization(
            @Value("${agent-platform.security.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/**");
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled) return true;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AccessDeniedException("JWT authentication is required");
        }
        String licenseCode = request.getHeader(AgentApiHeaders.LICENSE_CODE);
        Object licenses = jwt.getToken().getClaims().get("license_codes");
        if (licenseCode == null || !(licenses instanceof Collection<?> allowed)
                || allowed.stream().map(Object::toString).noneMatch(licenseCode::equals)) {
            throw new AccessDeniedException("Authenticated user cannot access this license code");
        }
        return true;
    }
}
