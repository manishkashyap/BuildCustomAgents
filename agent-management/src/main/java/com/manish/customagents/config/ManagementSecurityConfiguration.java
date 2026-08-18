package com.manish.customagents.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ManagementSecurityConfiguration {
    @Bean
    @ConditionalOnProperty(name = "agent-platform.security.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain localManagementSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }

    @Bean
    @ConditionalOnProperty(name = "agent-platform.security.enabled", havingValue = "true")
    SecurityFilterChain authenticatedManagementSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/openapi/**", "/swagger-ui/**", "/swagger-ui.html")
                            .hasAnyAuthority("AGENT_ADMIN", "PLATFORM_ADMIN", "AGENT_EDITOR", "AGENT_PUBLISHER")
                        .requestMatchers("/api/v1/**").hasAnyAuthority(
                                "AGENT_ADMIN", "PLATFORM_ADMIN", "AGENT_EDITOR", "AGENT_PUBLISHER")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    private Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> new JwtAuthenticationToken(jwt, authorities(jwt), jwt.getSubject());
    }

    private Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        Object roles = jwt.getClaims().get("roles");
        if (roles instanceof Collection<?> values) {
            values.forEach(value -> authorities.add(new SimpleGrantedAuthority(value.toString())));
        }
        return authorities;
    }
}
