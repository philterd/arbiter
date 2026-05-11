/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.security;

import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.ApiKeyHashingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.Map;

@Configuration
public class SecurityConfig {

    /**
     * Default for new password hashes: BCrypt with cost 12. Existing SHA-512 hashes that
     * pre-date this change are still accepted at login time via the legacy fallback below
     * (they have no encoder prefix). Once a user changes their password — or an admin
     * resets it — the new hash is BCrypt, prefixed with {@code {bcrypt}}.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
        final DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder("bcrypt",
                Map.of(
                        "bcrypt", bcrypt,
                        "sha512salted", new Sha512PasswordEncoder()));
        // Unprefixed hashes (the historical {saltHex}${sha512Hex} format) are matched as
        // legacy SHA-512 — so existing users can still sign in until their password is rotated.
        delegating.setDefaultPasswordEncoderForMatches(new Sha512PasswordEncoder());
        return delegating;
    }

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(final UserRepository userRepository,
                                             final ApiKeyHashingService apiKeyHashingService) {
        return new ApiKeyAuthFilter(userRepository, apiKeyHashingService);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public MfaAuthenticationSuccessHandler mfaAuthenticationSuccessHandler(
            final UserRepository userRepository,
            final SecurityContextRepository securityContextRepository) {
        return new MfaAuthenticationSuccessHandler(userRepository, securityContextRepository);
    }

    @Bean
    public AuthenticationProvider authenticationProvider(final UserDetailsService userDetailsService,
                                                         final PasswordEncoder passwordEncoder,
                                                         final LoginAttemptService loginAttempts,
                                                         final ai.philterd.arbiter.service.AuditLogService auditLogService) {
        return new LockAwareDaoAuthenticationProvider(userDetailsService, passwordEncoder,
                loginAttempts, auditLogService);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity http,
                                                   final ApiKeyAuthFilter apiKeyAuthFilter,
                                                   final AuditLogoutHandler auditLogoutHandler,
                                                   final MfaAuthenticationSuccessHandler mfaSuccessHandler,
                                                   final AuthenticationProvider authenticationProvider) throws Exception {
        // Send LockedException to /login?locked so the user sees a distinct message;
        // every other AuthenticationException maps to the existing /login?error.
        final ExceptionMappingAuthenticationFailureHandler failureHandler =
                new ExceptionMappingAuthenticationFailureHandler();
        failureHandler.setExceptionMappings(Map.of(LockedException.class.getName(), "/login?locked"));
        failureHandler.setDefaultFailureUrl("/login?error");

        // Paths that ADMIN-or-AUDITOR can read but only ADMIN may mutate. Listed once and
        // reused for the GET-vs-other-method split below so the two matchers stay in sync.
        // /batches and /batches/** are intentionally NOT in this list — those endpoints
        // also serve TEAM LEADs (per-group lead authority), and per-resource lead checks
        // are a per-endpoint decision the controller makes, not something the framework
        // matchers can express. They fall through to .anyRequest().authenticated() and the
        // controller gates writes via BatchAccessService.canLeadBatch.
        final String[] adminScopedPaths = {
                "/admin/**", "/reporting",
                "/policies", "/policies/**",
                "/api/v1/policies", "/api/v1/policies/**"
        };

        http
                .authenticationProvider(authenticationProvider)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/mfa", "/invitations/**",
                                "/css/**", "/js/**", "/images/**", "/webjars/**", "/docs/**", "/error").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Admin → Tools hosts destructive maintenance actions (today: data
                        // import job cleanup). Auditors are read-only and shouldn't even see
                        // the page, so this path is admin-only for both GETs and writes —
                        // listed BEFORE the auditor-readable adminScopedPaths matcher so the
                        // tighter rule wins.
                        .requestMatchers("/admin/tools", "/admin/tools/**").hasRole("ADMIN")
                        // Reads on admin-scoped paths: admins and auditors. Auditors are a
                        // global read role — they see the same cross-group data admins see
                        // (audit log, queue, batches, reports) but never mutate state.
                        .requestMatchers(HttpMethod.GET, adminScopedPaths).hasAnyRole("ADMIN", "AUDITOR")
                        // Anything else on the same paths (POST/PUT/PATCH/DELETE) — admin only.
                        .requestMatchers(adminScopedPaths).hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .usernameParameter("email")
                        .successHandler(mfaSuccessHandler)
                        .failureHandler(failureHandler)
                        .permitAll())
                .logout(logout -> logout
                        .addLogoutHandler(auditLogoutHandler)
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        new AntPathRequestMatcher("/api/**")))
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // Drop session-cookie authentication on /api/** so only Bearer keys count;
                // runs after the Bearer filter (which marks the request) and before the
                // authorization gate that decides 200 vs 401.
                .addFilterBefore(new ApiSessionRejectingFilter(), AuthorizationFilter.class)
                // Enforce the AUDITOR read-only contract on every path the
                // authorizeHttpRequests matchers don't already gate to ADMIN. Runs after
                // both authentication filters so the SecurityContext is populated, and
                // before AuthorizationFilter so the 403 it returns is the one the user sees.
                .addFilterBefore(new AuditorWriteRejectFilter(), AuthorizationFilter.class)
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));
        return http.build();
    }
}
