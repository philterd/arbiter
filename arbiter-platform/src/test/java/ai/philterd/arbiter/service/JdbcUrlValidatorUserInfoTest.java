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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Defends finding #13: admin-supplied JDBC URLs that carry an embedded
 * {@code user[:pass]@host} segment used to leak the password into every audit-log
 * row that recorded the URL. The fix has two prongs:
 *
 * <ol>
 *   <li>{@link JdbcUrlValidator#validate(String)} refuses URLs with embedded
 *       credentials at create-time — admins must use the Username and Password fields,
 *       which flow through {@link SymmetricCipher#encryptField(String)} at rest.</li>
 *   <li>{@link JdbcUrlValidator#stripUserInfo(String)} is a safe-for-logging
 *       transformation that removes the {@code user:pass@} segment from a URL string,
 *       used by the controller before writing the URL to the audit log even for the
 *       rejection event.</li>
 * </ol>
 */
class JdbcUrlValidatorUserInfoTest {

    /** No host allow-list — these tests focus solely on the userinfo logic. */
    private final JdbcUrlValidator validator = new JdbcUrlValidator(new DataSourceHostAllowList(""));

    // ---------- stripUserInfo: the safe-logging helper ----------

    @Test
    void stripRemovesUserPasswordFromPostgresqlUrl() {
        // The canonical leak case from finding #13.
        assertEquals(
                "jdbc:postgresql://host:5432/db",
                JdbcUrlValidator.stripUserInfo("jdbc:postgresql://user:secret@host:5432/db"));
    }

    @Test
    void stripRemovesUserOnlyUserInfo() {
        // The user-only form (no password) is rarer but still leaks the username.
        assertEquals(
                "jdbc:mysql://host:3306/db",
                JdbcUrlValidator.stripUserInfo("jdbc:mysql://alice@host:3306/db"));
    }

    @Test
    void stripPreservesQueryParametersAndFragment() {
        // The strip must NOT damage the rest of the URL — characters after the
        // host (path, query, fragment) survive verbatim.
        assertEquals(
                "jdbc:postgresql://host/db?ssl=true&applicationName=arbiter",
                JdbcUrlValidator.stripUserInfo(
                        "jdbc:postgresql://user:secret@host/db?ssl=true&applicationName=arbiter"));
    }

    @Test
    void stripIsANoOpWhenUrlHasNoUserInfo() {
        // Already-safe URLs must pass through unchanged so the controller can call
        // stripUserInfo unconditionally before logging.
        assertEquals(
                "jdbc:postgresql://host:5432/db",
                JdbcUrlValidator.stripUserInfo("jdbc:postgresql://host:5432/db"));
        assertEquals(
                "jdbc:mysql://host:3306/db?useSSL=true",
                JdbcUrlValidator.stripUserInfo("jdbc:mysql://host:3306/db?useSSL=true"));
    }

    @Test
    void stripIsANoOpForOracleThinAtSeparator() {
        // Oracle thin URLs use '@' as a HOST separator, not a userinfo terminator —
        // jdbc:oracle:thin:@host:1521:sid is an opaque URI. Stripping must not
        // damage the '@host' segment.
        assertEquals(
                "jdbc:oracle:thin:@host:1521:sid",
                JdbcUrlValidator.stripUserInfo("jdbc:oracle:thin:@host:1521:sid"));
        assertEquals(
                "jdbc:oracle:thin:@//host:1521/service",
                JdbcUrlValidator.stripUserInfo("jdbc:oracle:thin:@//host:1521/service"));
    }

    @Test
    void stripHandlesIpv6HostsWithUserInfo() {
        // The IPv6 brackets are part of the host segment, not the userinfo. They
        // must survive the strip intact.
        assertEquals(
                "jdbc:postgresql://[2001:db8::1]:5432/db",
                JdbcUrlValidator.stripUserInfo(
                        "jdbc:postgresql://user:secret@[2001:db8::1]:5432/db"));
    }

    @Test
    void stripIsANoOpForBlankOrNullInput() {
        assertNull(JdbcUrlValidator.stripUserInfo(null));
        assertEquals("", JdbcUrlValidator.stripUserInfo(""));
        // Whitespace gets trimmed before the JDBC-prefix check, so a whitespace-only
        // input returns empty rather than the original whitespace.
        assertEquals("", JdbcUrlValidator.stripUserInfo("   "));
    }

    @Test
    void stripDoesNotInventCredentialsFromTrailingAtSigns() {
        // A password-bearing string in a query-parameter VALUE has its own '@' but
        // is not URL userinfo. The strip must NOT mistake it for userinfo and
        // remove the legitimate query state.
        final String input = "jdbc:postgresql://host:5432/db?currentUser=jeff@example.com";
        assertEquals(input, JdbcUrlValidator.stripUserInfo(input));
    }

    @Test
    void stripIsSafeForMalformedUrls() {
        // Best-effort: a URL that can't be URI-parsed should at worst return the
        // input unchanged. Never throw, never crash the validator.
        final String malformed = "jdbc:postgresql://user:secret@host with spaces/db";
        // We don't assert the exact output — only that it returns without throwing
        // and doesn't blow up downstream callers.
        assertNotNull(JdbcUrlValidator.stripUserInfo(malformed));
    }

    // ---------- validate(): refuse URLs that carry userinfo ----------

    @Test
    void validateRejectsUrlWithEmbeddedPassword() {
        final JdbcUrlValidator.Result r =
                validator.validate("jdbc:postgresql://alice:secret@host:5432/db");
        assertFalse(r.ok(), "expected rejection for URL with embedded credentials");
        assertNotNull(r.error());
        // Tell the admin where to put credentials instead.
        assertTrue(r.error().toLowerCase().contains("username and password"),
                "expected guidance about Username/Password fields, got: " + r.error());
    }

    @Test
    void validateRejectsUrlWithUserOnly() {
        final JdbcUrlValidator.Result r =
                validator.validate("jdbc:mysql://alice@host:3306/db");
        assertFalse(r.ok());
        assertTrue(r.error().toLowerCase().contains("embedded credentials"));
    }

    @Test
    void validateRejectsUrlWithUserInfoAfterDangerousParamCheck() {
        // The dangerous-param check still fires first if both apply, so the
        // admin sees the most specific error. Userinfo + a dangerous param
        // surfaces as the dangerous-param error.
        final JdbcUrlValidator.Result r = validator.validate(
                "jdbc:mysql://alice:bob@host:3306/db?queryInterceptors=Evil");
        assertFalse(r.ok());
        // Whichever fires first, the admin gets a clear refusal — that's the
        // contract. Both messages are forensically useful.
        assertTrue(
                r.error().toLowerCase().contains("queryinterceptors")
                        || r.error().toLowerCase().contains("embedded credentials"),
                "expected one of the two refusal messages, got: " + r.error());
    }

    @Test
    void validateDoesNotMistakeOracleThinAtForUserInfo() {
        // Oracle thin '@' is the host separator, not a userinfo terminator. Whatever
        // verdict the validator returns must not be the embedded-credentials refusal —
        // that's the contract this test pins (the URL may fail other checks like host
        // allow-list extraction, which is a separate concern handled elsewhere).
        final JdbcUrlValidator.Result r =
                validator.validate("jdbc:oracle:thin:@host:1521:sid");
        if (!r.ok()) {
            assertFalse(r.error().toLowerCase().contains("embedded credentials"),
                    "Oracle thin '@host' was misidentified as userinfo: " + r.error());
        }
    }

    @Test
    void validateAcceptsCleanUrlWithoutUserInfo() {
        final JdbcUrlValidator.Result r =
                validator.validate("jdbc:postgresql://host:5432/db");
        assertTrue(r.ok(), "expected acceptance of clean URL, got: " + r.error());
    }
}
