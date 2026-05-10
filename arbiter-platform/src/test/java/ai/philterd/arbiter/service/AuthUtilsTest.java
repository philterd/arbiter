/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Centralized helper used by every controller's authorization branch. A regression here
 * would silently change role gating across the whole app, so the tests cover the obvious
 * positives, the obvious negatives, and a handful of defensive shapes (null, no
 * authorities, anonymous, multiple roles, case sensitivity).
 */
class AuthUtilsTest {

    @Test
    void nullAuthenticationIsNeitherAdminNorAuditor() {
        assertFalse(AuthUtils.isAdmin(null));
        assertFalse(AuthUtils.isAuditor(null));
        assertFalse(AuthUtils.isAdminOrAuditor(null));
    }

    @Test
    void adminTokenIsAdminButNotAuditor() {
        final Authentication admin = withRoles("ROLE_ADMIN");
        assertTrue(AuthUtils.isAdmin(admin));
        assertFalse(AuthUtils.isAuditor(admin));
        assertTrue(AuthUtils.isAdminOrAuditor(admin));
    }

    @Test
    void auditorTokenIsAuditorButNotAdmin() {
        final Authentication auditor = withRoles("ROLE_AUDITOR");
        assertFalse(AuthUtils.isAdmin(auditor));
        assertTrue(AuthUtils.isAuditor(auditor));
        assertTrue(AuthUtils.isAdminOrAuditor(auditor));
    }

    @Test
    void plainUserTokenIsNeither() {
        // The only role-bearing token in this set is ROLE_USER, which corresponds to a
        // group-scoped reviewer — must not satisfy either admin or auditor checks.
        final Authentication reviewer = withRoles("ROLE_USER");
        assertFalse(AuthUtils.isAdmin(reviewer));
        assertFalse(AuthUtils.isAuditor(reviewer));
        assertFalse(AuthUtils.isAdminOrAuditor(reviewer));
    }

    @Test
    void roleNameIsCaseSensitive() {
        // Case-sensitive comparison is intentional — Spring Security canonicalises authority
        // names to ROLE_ + UPPER_SNAKE_CASE. A lowercase "role_admin" must not authenticate
        // as admin; otherwise a stray helper that builds authorities from raw text could
        // bypass the gate.
        assertFalse(AuthUtils.isAdmin(withRoles("role_admin")),
                "Lowercase variants of the admin authority must NOT pass the check.");
        assertFalse(AuthUtils.isAdmin(withRoles("ADMIN")),
                "Authority strings without the ROLE_ prefix must NOT pass the check.");
    }

    @Test
    void multipleAuthoritiesAreSearchedExhaustively() {
        // The token has a non-matching authority before the match — verifies the iterator
        // continues past the first miss.
        final Authentication auth = withRoles("ROLE_USER", "ROLE_ADMIN");
        assertTrue(AuthUtils.isAdmin(auth));
        assertTrue(AuthUtils.isAdminOrAuditor(auth));
    }

    @Test
    void authenticationWithNoAuthoritiesIsNeitherAdminNorAuditor() {
        final Authentication noAuthorities = new UsernamePasswordAuthenticationToken(
                "x@x.com", null, List.of());
        assertFalse(AuthUtils.isAdmin(noAuthorities));
        assertFalse(AuthUtils.isAuditor(noAuthorities));
        assertFalse(AuthUtils.isAdminOrAuditor(noAuthorities));
    }

    @Test
    void anonymousTokenWithRoleAnonymousIsNotAdminOrAuditor() {
        // Spring's anonymous authentication carries ROLE_ANONYMOUS — which must NOT match
        // either elevated role.
        final Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser",
                List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        assertFalse(AuthUtils.isAdmin(anonymous));
        assertFalse(AuthUtils.isAuditor(anonymous));
        assertFalse(AuthUtils.isAdminOrAuditor(anonymous));
    }

    @Test
    void custodianRoleNamesDoNotAccidentallyMatchAdmin() {
        // Anti-prefix-bug regression: an authority named "ROLE_ADMINISTRATOR" or
        // "ROLE_ADMINS" must not satisfy isAdmin — equality, not startsWith.
        assertFalse(AuthUtils.isAdmin(withRoles("ROLE_ADMINISTRATOR")));
        assertFalse(AuthUtils.isAdmin(withRoles("ROLE_ADMINS")));
        assertFalse(AuthUtils.isAuditor(withRoles("ROLE_AUDITORS")));
    }

    private static Authentication withRoles(final String... roles) {
        final Set<SimpleGrantedAuthority> authorities = new java.util.LinkedHashSet<>();
        for (String r : roles) authorities.add(new SimpleGrantedAuthority(r));
        return new UsernamePasswordAuthenticationToken("user@example.com", null, authorities);
    }
}
