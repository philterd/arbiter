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
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
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

    /**
     * Tracks live sessions per principal so we can expire them on
     * password change, role change, or account deletion (R2-F12). Without this
     * an attacker who has hijacked a session keeps full access even after the
     * legitimate user rotates their credentials.
     *
     * <p>{@link HttpSessionEventPublisher} forwards servlet container session
     * lifecycle events to the registry so it stays in sync with reality.
     *
     * <p>For Spring-Session-backed deployments (Redis/Valkey), swap this bean
     * for {@code SpringSessionBackedSessionRegistry} so the per-principal
     * tracking is shared across replicas. Today's prod compose runs with
     * {@code SPRING_SESSION_STORE_TYPE=none}, so the in-memory registry is
     * correct for that deployment.
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
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
                        // OpenAPI / Swagger UI is admin-only (R2-F2). Previously
                        // permitAll(), which let any unauthenticated caller enumerate
                        // every admin endpoint and DTO shape — free reconnaissance.
                        // The /v3/api-docs and /swagger-ui paths are also disabled by
                        // default at the springdoc level (application.properties); set
                        // ARBITER_OPENAPI_ENABLED=true on a deployment that needs them.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").hasRole("ADMIN")
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
                // Plug the SessionRegistry into the security pipeline so the
                // password-change / role-change paths can expire other live
                // sessions for the principal (R2-F12). maximumSessions(-1) =
                // unlimited concurrent sessions — we're not enforcing a cap
                // here, just registering each session so it can be expired
                // on demand.
                .sessionManagement(s -> s
                        .sessionFixation().changeSessionId()
                        .maximumSessions(-1).sessionRegistry(sessionRegistry()))
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
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                // Defence-in-depth response headers. Arbiter renders document text
                // and reviewer comments through Thymeleaf (escaped via th:text), but
                // any future regression introducing an unescaped sink would otherwise
                // execute with full document scope and could fetch /api/v1/** to
                // exfiltrate PII. The CSP below restricts script/object/base/form
                // sources to same-origin only, blocking the exfil chain.
                //
                // HSTS protects against first-visit downgrade for deployments behind
                // a TLS-terminating reverse proxy (production model — see
                // server.forward-headers-strategy=framework).
                // CSP is written by CspNonceFilter (per-request nonce so legitimate
                // inline scripts run while attacker-injected ones — which can't know
                // the nonce — are refused). It MUST run before view rendering so the
                // nonce is exposed as a request attribute Thymeleaf can read; a
                // HeaderWriter would fire too late (at response-commit time, after the
                // template has already resolved ${cspNonce} to null and dropped every
                // <script> tag's nonce attribute). Don't also write a static
                // script-src 'self' via the default DSL: the two would race and the
                // static one would either replace the nonced policy or land alongside
                // it, both of which neutralise the protection.
                .addFilterBefore(new CspNonceFilter(),
                        org.springframework.security.web.header.HeaderWriterFilter.class)
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)
                                .preload(true))
                        .referrerPolicy(rp -> rp.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicy(pp -> pp.policy(
                                "geolocation=(), microphone=(), camera=(), payment=(), "
                                        + "usb=(), midi=(), magnetometer=(), gyroscope=(), accelerometer=()")));
        return http.build();
    }
}
