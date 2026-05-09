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

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Tiny static helpers for authentication checks that controllers (and a couple of services)
 * apply repeatedly. Centralising them here is purely about avoiding drift — every caller
 * compares against the literal {@code "ROLE_ADMIN"} authority in the same way, and a single
 * stray "admin"/"ADMIN" mismatch would be a security boundary bug.
 */
public final class AuthUtils {

    private AuthUtils() {
    }

    /**
     * Returns {@code true} if the principal carries the {@code ROLE_ADMIN} authority.
     * Treats a {@code null} authentication as not-admin so callers can pass through
     * unauthenticated principals without a separate guard.
     */
    public static boolean isAdmin(final Authentication auth) {
        return hasAuthority(auth, "ROLE_ADMIN");
    }

    /**
     * Returns {@code true} if the principal carries the {@code ROLE_AUDITOR} authority.
     * Auditors see the same cross-group reads an admin sees but never mutate state —
     * the {@code AuditorWriteRejectFilter} on {@code SecurityConfig} enforces the
     * read-only contract.
     */
    public static boolean isAuditor(final Authentication auth) {
        return hasAuthority(auth, "ROLE_AUDITOR");
    }

    /**
     * Returns {@code true} when the principal is either an admin or an auditor — both
     * roles see the cross-group view (queue, search, audit log, etc.). Use this in
     * controller branches that decide between "see everything" and "see only my groups."
     * For branches that gate WRITES, use {@link #isAdmin(Authentication)} instead so
     * auditors stay read-only.
     */
    public static boolean isAdminOrAuditor(final Authentication auth) {
        return isAdmin(auth) || isAuditor(auth);
    }

    private static boolean hasAuthority(final Authentication auth, final String name) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (name.equals(a.getAuthority())) return true;
        }
        return false;
    }
}
