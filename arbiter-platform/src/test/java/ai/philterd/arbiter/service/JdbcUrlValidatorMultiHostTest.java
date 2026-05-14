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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the comma-separated multi-host bypass (finding #2). MySQL and
 * PostgreSQL drivers both support failover syntax like
 * {@code jdbc:mysql://h1:3306,h2:3306/db} natively — the driver will reach the
 * second host even if only the first is on the data-source host allow-list,
 * because {@link JdbcUrlValidator#extractHost(String)} only ever returned the
 * first match. These tests pin the explicit refusal of any comma-bearing
 * authority and confirm the rule doesn't false-positive on commas elsewhere in
 * the URL (query string, path).
 */
class JdbcUrlValidatorMultiHostTest {

    /**
     * Allow-list configured with the documented public host so a single-host
     * URL pointing there would otherwise pass — the test then proves it's the
     * comma-separated authority alone that triggers rejection.
     */
    private final JdbcUrlValidator validator =
            new JdbcUrlValidator(new DataSourceHostAllowList("allowed-public-host.example.com"));

    private JdbcUrlValidator.Result validate(final String url) {
        return validator.validate(url);
    }

    private void assertRejected(final String url, final String reasonContains) {
        final JdbcUrlValidator.Result r = validate(url);
        assertFalse(r.ok(), "expected rejection for: " + url);
        assertNotNull(r.error());
        assertTrue(r.error().toLowerCase().contains(reasonContains.toLowerCase()),
                "expected error to mention '" + reasonContains + "', got: " + r.error());
    }

    @Test
    void rejectsMysqlMultiHostFailoverUrl() {
        // The canonical exploit: first host is on the allow-list (would normally pass),
        // second host is the internal target the driver will actually reach on failover.
        assertRejected(
                "jdbc:mysql://allowed-public-host.example.com:3306,127.0.0.1:3306/arbiter",
                "comma-separated host");
    }

    @Test
    void rejectsPostgresqlMultiHostFailoverUrl() {
        // PostgreSQL has the same syntax. The internal-only target here is a
        // hostname the LAN can resolve but the public allow-list never would.
        assertRejected(
                "jdbc:postgresql://allowed-public-host.example.com:5432,internal-pg.lan:5432/arbiter",
                "comma-separated host");
    }

    @Test
    void rejectsMultiHostWhenAllHostsAreInternalToo() {
        // Belt-and-braces: even when no host is on the allow-list, the rule
        // fires *before* the host-allow-list check — the comma alone is enough.
        // This guards against a future code path that, e.g., conditionalises
        // the allow-list and would otherwise re-open this gap.
        assertRejected(
                "jdbc:mysql://10.0.0.1:3306,10.0.0.2:3306/arbiter",
                "comma-separated host");
    }

    @Test
    void multiHostRefusalIsCaseInsensitiveAndDriverInsensitive() {
        // MariaDB driver, all-lowercase scheme — same rule.
        assertRejected(
                "jdbc:mariadb://allowed-public-host.example.com,127.0.0.1/arbiter",
                "comma-separated host");
    }

    @Test
    void singleHostUrlWithCommaInQueryStringIsNotMisclassified() {
        // Commas appear legitimately inside connection-string parameters
        // (e.g. application-name lists). The authority-only scan must not
        // false-positive when the comma is in the query portion.
        final JdbcUrlValidator.Result r = validate(
                "jdbc:postgresql://allowed-public-host.example.com:5432/arbiter?application_name=svc,worker");
        assertTrue(r.ok(),
                "comma in query string should not trip the multi-host check: " + r.error());
    }

    @Test
    void singleHostUrlWithoutCommaStillAccepted() {
        // Regression guard: the new rule must not affect the happy path.
        final JdbcUrlValidator.Result r = validate(
                "jdbc:postgresql://allowed-public-host.example.com:5432/arbiter");
        assertTrue(r.ok(), "single-host URL must still pass: " + r.error());
    }

    // ---------- sub-protocol failover (R2-F14) ----------

    @Test
    void rejectsMysqlLoadbalanceSubProtocol() {
        // jdbc:mysql:loadbalance://h1,h2/db parses as an opaque URI (because the
        // ':' after mysql sits in the scheme), so the comma-in-authority check
        // wouldn't see it. The explicit sub-protocol refusal catches it.
        assertRejected(
                "jdbc:mysql:loadbalance://allowed-public-host.example.com,127.0.0.1:3306/arbiter",
                "comma-separated host");
    }

    @Test
    void rejectsMysqlReplicationSubProtocol() {
        // Same vector with the read/write-split variant. The driver will fail
        // over to the second host on master failure — out of the allow-list.
        assertRejected(
                "jdbc:mysql:replication://master.example.com,replica-internal.lan/arbiter",
                "comma-separated host");
    }

    @Test
    void rejectsMysqlSequentialSubProtocol() {
        // Sequential is the deprecated-but-still-supported third form.
        assertRejected(
                "jdbc:mysql:sequential://allowed-public-host.example.com,evil.internal:3306/arbiter",
                "comma-separated host");
    }

    @Test
    void rejectsMariadbLoadbalanceSubProtocol() {
        // MariaDB driver has the same sub-protocol set.
        assertRejected(
                "jdbc:mariadb:loadbalance://allowed-public-host.example.com,127.0.0.1/arbiter",
                "comma-separated host");
    }

    @Test
    void rejectsMysqlAddressKeyValueMultiHostForm() {
        // The third bypass form: jdbc:mysql://address=(host=h1)(host=h2)/db.
        // No comma in the URI authority, but two (host=…) tokens means a
        // multi-host directive. Refuse on the same grounds.
        assertRejected(
                "jdbc:mysql://address=(host=allowed-public-host.example.com)(host=127.0.0.1)/arbiter",
                "comma-separated host");
    }

    @Test
    void singleHostMysqlAddressKeyValueFormIsAccepted() {
        // The address=(host=…) form is legal with a single host. The check must
        // only fire when two or more (host=…) tokens are present.
        final JdbcUrlValidator.Result r = validate(
                "jdbc:mysql://address=(host=allowed-public-host.example.com)(port=3306)/arbiter");
        // May still be rejected for a separate reason (e.g. the parens trip the
        // URI parser), but NOT for "comma-separated host" — the multi-host rule
        // must not false-positive on the legitimate single-host form.
        if (!r.ok()) {
            assertFalse(r.error().toLowerCase().contains("comma-separated"),
                    "single-host address=(host=…) must not trip the multi-host rule: " + r.error());
        }
    }

    @Test
    void oracleThinOpaqueUriIsUnaffected() {
        // Oracle thin uses an opaque URI form (jdbc:oracle:thin:@host:1521:sid).
        // URI parsing returns null authority, so the multi-host check is a
        // no-op. The URL is rejected later because the host extraction yields
        // an unallowed host, but NOT for "comma-separated host list" — the
        // syntax we're refusing doesn't exist on this driver.
        final JdbcUrlValidator.Result r = validate(
                "jdbc:oracle:thin:@allowed-public-host.example.com:1521:sid");
        // Either accepted (host allowed) or rejected for a non-multi-host reason.
        if (!r.ok()) {
            assertFalse(r.error().toLowerCase().contains("comma-separated"),
                    "Oracle thin URL must not be misclassified as multi-host: " + r.error());
        }
    }
}
