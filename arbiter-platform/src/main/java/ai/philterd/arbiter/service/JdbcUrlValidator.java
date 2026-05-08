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

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates an admin-supplied JDBC URL before it is saved as a data source. Defends against
 * driver features that execute code at connect time — most notoriously H2's {@code INIT=}
 * (runs arbitrary SQL when the connection opens) and PostgreSQL/MySQL deserialization
 * gadgets in {@code socketFactory=}/{@code autoDeserialize=true} — by enforcing three rules:
 *
 * <ol>
 *   <li><strong>Scheme allow-list:</strong> only the major server-style drivers are
 *       accepted. Embedded/file-backed engines (H2, Derby, HSQLDB, SQLite) are refused
 *       outright because their URL parameters can run SQL or load JARs at connect time.</li>
 *   <li><strong>Dangerous-parameter block-list:</strong> any of the substrings in
 *       {@link #DANGEROUS_PARAMS} appearing anywhere in the URL (case-insensitive) is
 *       refused. The check is intentionally crude because driver param parsers are
 *       inconsistent across versions; refusing the whole URL is safer than guessing.</li>
 *   <li><strong>Host allow-list (opt-in):</strong> when the deployment configures
 *       {@code arbiter.data-sources.allowed-hosts} via {@link DataSourceHostAllowList},
 *       the host portion of the JDBC URL must be on the list. This is the same
 *       defense-in-depth host pinning that F8 added for OpenSearch / Elasticsearch.</li>
 * </ol>
 *
 * <p>The validator does not connect to anything — it inspects the URL string only.
 */
@Component
public class JdbcUrlValidator {

    /**
     * Drivers known to run code at connect time, exposing the JVM to RCE if the admin
     * (or finding-#2-style XSS in an admin's browser) supplies a hostile URL. Rejected
     * before the scheme allow-list even comes into play so the error message is specific.
     */
    public static final Set<String> EMBEDDED_DRIVERS = Set.of(
            "h2", "derby", "hsqldb", "sqlite");

    /**
     * Default scheme allow-list — the major server-style drivers. Override only if the
     * deployment deliberately needs a less common driver and has independently audited
     * its URL surface.
     */
    public static final Set<String> DEFAULT_ALLOWED_DRIVERS = Set.of(
            "postgresql", "mysql", "mariadb", "sqlserver", "oracle", "db2");

    /**
     * Substrings that, if present anywhere in the JDBC URL (case-insensitive), are
     * grounds for refusal. These are well-known driver-specific levers used in JDBC
     * RCE chains:
     *
     * <ul>
     *   <li>{@code init=} — H2 runs the SQL on connect.</li>
     *   <li>{@code socketfactory} — PostgreSQL JDBC pre-42.2.5 + ObjectInputStream gadgets.</li>
     *   <li>{@code autodeserialize} — MySQL Connector/J deserialization gadget chain.</li>
     *   <li>{@code allowloadlocalinfile} / {@code allowurlinlocalinfile} —
     *       MySQL {@code LOAD DATA LOCAL INFILE} primitive turned into arbitrary file read.</li>
     *   <li>{@code allowmultiqueries} — defensive: multi-statement support has fueled
     *       past second-order injection chains.</li>
     *   <li>{@code script=}, {@code runscript=} — H2 / HSQLDB equivalents of {@code init=}.</li>
     *   <li>{@code restoreFrom=} — Derby connect-time DB restore from filesystem path.</li>
     * </ul>
     */
    public static final List<String> DANGEROUS_PARAMS = List.of(
            "init=",
            "socketfactory",
            "autodeserialize",
            "allowloadlocalinfile",
            "allowurlinlocalinfile",
            "allowmultiqueries",
            "script=",
            "runscript=",
            "restorefrom=");

    /**
     * After the {@code jdbc:<driver>:} prefix, this pattern matches the host portion of
     * the most common URL forms:
     *
     * <ul>
     *   <li>{@code jdbc:postgresql://host[:port]/db}</li>
     *   <li>{@code jdbc:mysql://host[:port]/db}</li>
     *   <li>{@code jdbc:sqlserver://host[:port];...}</li>
     *   <li>{@code jdbc:oracle:thin:@host:port:sid}</li>
     *   <li>{@code jdbc:oracle:thin:@//host:port/service}</li>
     * </ul>
     */
    private static final Pattern HOST_PATTERN = Pattern.compile(
            "(?:jdbc:[a-zA-Z0-9]+(?::[a-zA-Z0-9]+)*:)" // jdbc:<driver>[:<sub>]:
                    + "(?://|@//?)"                     // // or @ or @//
                    + "([^/:;?,#]+)",                   // host (no path/port/sep chars)
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DRIVER_PATTERN = Pattern.compile(
            "^jdbc:([a-zA-Z0-9]+)", Pattern.CASE_INSENSITIVE);

    private final Set<String> allowedDrivers;
    private final DataSourceHostAllowList hostAllowList;

    public JdbcUrlValidator(final DataSourceHostAllowList hostAllowList) {
        this(hostAllowList, DEFAULT_ALLOWED_DRIVERS);
    }

    /** Test seam — lets a test override the driver allow-list. */
    public JdbcUrlValidator(final DataSourceHostAllowList hostAllowList,
                            final Set<String> allowedDrivers) {
        this.hostAllowList = hostAllowList;
        this.allowedDrivers = new LinkedHashSet<>(allowedDrivers);
    }

    /** Outcome of a validation. {@link #error()} is null when {@link #ok()} is true. */
    public record Result(boolean ok, String error) {
        public static Result accepted() { return new Result(true, null); }
        public static Result rejected(final String message) { return new Result(false, message); }
    }

    /**
     * Inspect a JDBC URL and return whether it is acceptable. The checks run in order so
     * the error returned is the most specific one applicable.
     */
    public Result validate(final String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return Result.rejected("JDBC URL is required.");
        }
        final String url = jdbcUrl.trim();
        final String lower = url.toLowerCase(Locale.ROOT);

        if (!lower.startsWith("jdbc:")) {
            return Result.rejected("JDBC URL must start with \"jdbc:\".");
        }

        final Matcher driverMatcher = DRIVER_PATTERN.matcher(url);
        if (!driverMatcher.find()) {
            return Result.rejected("JDBC URL is missing a driver scheme (expected jdbc:<driver>:...).");
        }
        final String driver = driverMatcher.group(1).toLowerCase(Locale.ROOT);

        if (EMBEDDED_DRIVERS.contains(driver)) {
            return Result.rejected("Embedded JDBC driver \"" + driver
                    + "\" is not allowed — its URL parameters can run code at connect time.");
        }

        if (!allowedDrivers.contains(driver)) {
            return Result.rejected("JDBC driver \"" + driver + "\" is not on the allow-list. "
                    + "Permitted drivers: " + String.join(", ", allowedDrivers) + ".");
        }

        for (String bad : DANGEROUS_PARAMS) {
            if (lower.contains(bad)) {
                return Result.rejected("JDBC URL contains the disallowed parameter \"" + bad
                        + "\". Driver-specific parameters that execute code on connect are blocked.");
            }
        }

        // Host allow-list — only enforced when the deployer has configured one, so this
        // is a no-op on installs that haven't opted in. When configured, applies to JDBC
        // URLs the same as it does to OpenSearch/Elasticsearch endpoints.
        if (hostAllowList != null && hostAllowList.isEnforced()) {
            final String host = extractHost(url);
            if (host == null || host.isBlank()) {
                return Result.rejected("JDBC URL host could not be parsed; cannot apply the "
                        + "data-source host allow-list.");
            }
            // Reuse the same matcher that DataSourceHostAllowList exposes by handing it
            // a synthetic http URL. This keeps wildcard / exact-match semantics consistent
            // across data-source types.
            if (!hostAllowList.isAllowed("http://" + host)) {
                return Result.rejected("JDBC URL host \"" + host
                        + "\" is not on the data-source allow-list "
                        + "(arbiter.data-sources.allowed-hosts).");
            }
        }

        return Result.accepted();
    }

    /**
     * Best-effort host extraction for the common JDBC URL forms. Returns {@code null} if
     * the URL doesn't look like one of the patterns the validator recognizes.
     */
    static String extractHost(final String jdbcUrl) {
        if (jdbcUrl == null) return null;
        final Matcher m = HOST_PATTERN.matcher(jdbcUrl);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
