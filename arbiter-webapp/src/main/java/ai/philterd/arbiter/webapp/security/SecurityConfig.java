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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    public ApiKeyAuthFilter apiKeyAuthFilter(final UserRepository userRepository) {
        return new ApiKeyAuthFilter(userRepository);
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
                                                         final LoginAttemptService loginAttempts) {
        return new LockAwareDaoAuthenticationProvider(userDetailsService, passwordEncoder, loginAttempts);
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

        http
                .authenticationProvider(authenticationProvider)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/mfa", "/invitations/**",
                                "/css/**", "/js/**", "/images/**", "/webjars/**", "/docs/**", "/error").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/admin/**", "/reporting",
                                "/batches", "/batches/**",
                                "/policies", "/policies/**",
                                "/api/v1/policies", "/api/v1/policies/**").hasRole("ADMIN")
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
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));
        return http.build();
    }
}
