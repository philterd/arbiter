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
 * Unit tests for {@link SqlReadOnlyValidator}, the allow-list defence that replaces the
 * prior {@code DELETE|TRUNCATE|DROP} keyword block-list on relational data-source SQL.
 * The block-list silently accepted {@code UPDATE / INSERT / MERGE / EXEC / CALL /
 * GRANT / ALTER / CREATE / REPLACE}; the allow-list refuses everything that isn't a
 * single {@code SELECT} or {@code WITH … SELECT} read.
 */
class SqlReadOnlyValidatorTest {

    private static void assertAccepted(final String sql) {
        final SqlReadOnlyValidator.Result r = SqlReadOnlyValidator.validate(sql);
        assertTrue(r.ok(), "expected acceptance for: " + sql + " — got: " + r.error());
    }

    private static void assertRejected(final String sql, final String reasonContains) {
        final SqlReadOnlyValidator.Result r = SqlReadOnlyValidator.validate(sql);
        assertFalse(r.ok(), "expected rejection for: " + sql);
        assertNotNull(r.error());
        assertTrue(r.error().toLowerCase().contains(reasonContains.toLowerCase()),
                "expected error to mention '" + reasonContains + "', got: " + r.error());
    }

    // ---------- accepted shapes ----------

    @Test
    void plainSelectIsAccepted() {
        assertAccepted("SELECT id, body FROM documents");
        assertAccepted("select id from documents"); // case-insensitive
        assertAccepted("  SELECT 1  "); // tolerant of surrounding whitespace
    }

    @Test
    void complexSelectShapesAreAccepted() {
        assertAccepted("SELECT t.a, COUNT(*) AS c "
                + "FROM documents t WHERE t.batch_id = 'b1' "
                + "GROUP BY t.a HAVING c > 1 "
                + "ORDER BY t.a LIMIT 100");
        assertAccepted("SELECT * FROM documents JOIN batches ON documents.batch_id = batches.id "
                + "WHERE batches.status = 'OPEN'");
    }

    @Test
    void withCteAcceptedWhenInnerStatementIsSelect() {
        assertAccepted("WITH recent AS (SELECT id FROM documents WHERE created > '2026-01-01') "
                + "SELECT * FROM recent");
    }

    @Test
    void mutationKeywordInsideStringLiteralIsIgnored() {
        // The literal contains DELETE, but it's a string, not executable SQL.
        assertAccepted("SELECT 'DELETE FROM users' AS demo FROM documents");
        assertAccepted("SELECT id FROM documents WHERE note = 'must DROP table'");
        // Doubled-quote escape inside the literal: 'O''Brien'.
        assertAccepted("SELECT id FROM documents WHERE author = 'O''Brien'");
    }

    @Test
    void mutationKeywordInsideBlockCommentIsIgnored() {
        // Block + line + hash comments are all stripped before scanning.
        assertAccepted("SELECT id /* DELETE FROM legacy_users */ FROM documents");
        assertAccepted("SELECT id FROM documents -- DROP TABLE backup");
        assertAccepted("SELECT id FROM documents # GRANT ALL ON x TO 'a'");
    }

    @Test
    void columnsThatLookLikeKeywordsAreAccepted() {
        // \b boundary requires a non-word char on each side. dropoff_count, deleted_at,
        // and select_count all contain the keyword as a substring but not as a whole
        // word, so the validator must not flag them.
        assertAccepted("SELECT dropoff_count, deleted_at, select_count FROM documents");
    }

    // ---------- mutation keywords (the bug class the block-list missed) ----------

    @Test
    void insertIsRejected() {
        assertRejected("INSERT INTO documents (id, body) VALUES ('a', 'b')", "INSERT");
    }

    @Test
    void updateIsRejected() {
        // The headline omission of the previous block-list: UPDATE was silently
        // accepted, letting an admin save a data source that mutates the remote DB.
        assertRejected("UPDATE documents SET status = 'APPROVED' WHERE id = 'd1'", "UPDATE");
    }

    @Test
    void mergeIsRejected() {
        assertRejected("MERGE INTO documents USING staging ON (documents.id = staging.id) "
                + "WHEN MATCHED THEN UPDATE SET body = staging.body", "MERGE");
    }

    @Test
    void execAndCallAndExecuteAreRejected() {
        assertRejected("EXEC sp_evil 'param'", "EXEC");
        assertRejected("CALL purge_old_documents()", "CALL");
        assertRejected("EXECUTE BLOCK AS BEGIN DELETE FROM documents; END", "EXECUTE");
    }

    @Test
    void grantAndRevokeAreRejected() {
        assertRejected("GRANT ALL ON documents TO 'evil'@'%'", "GRANT");
        assertRejected("REVOKE SELECT ON documents FROM 'auditor'", "REVOKE");
    }

    @Test
    void alterAndCreateAndDropAreRejected() {
        assertRejected("ALTER TABLE documents ADD COLUMN tampered BOOLEAN", "ALTER");
        assertRejected("CREATE TABLE shadow AS SELECT * FROM documents", "CREATE");
        assertRejected("DROP TABLE documents", "DROP");
        assertRejected("TRUNCATE TABLE documents", "TRUNCATE");
    }

    @Test
    void deleteWithCteIsRejectedEvenWithLeadingWith() {
        // PostgreSQL allows data-modifying CTEs: WITH d AS (DELETE … RETURNING …) SELECT.
        // The leading keyword is WITH (which would pass the leading-keyword gate alone),
        // but the mutation-keyword scan catches the inner DELETE.
        assertRejected("WITH d AS (DELETE FROM documents WHERE expired RETURNING id) "
                + "SELECT * FROM d", "DELETE");
    }

    @Test
    void setSessionLevelIsRejected() {
        // SET application_name / SET search_path / etc. are state-changing and have no
        // place in a read-only query. The validator refuses any whole-word SET.
        assertRejected("SET search_path TO public", "SET");
    }

    @Test
    void copyIsRejected() {
        // PostgreSQL COPY ... FROM/TO is the canonical file-read/write primitive used in
        // SQL-injection-to-file-system chains.
        assertRejected("COPY documents FROM '/etc/passwd'", "COPY");
    }

    @Test
    void lockAndLoadAreRejected() {
        assertRejected("LOCK TABLE documents IN EXCLUSIVE MODE", "LOCK");
        assertRejected("LOAD 'libexample'", "LOAD");
    }

    // ---------- multi-statement / structural rejections ----------

    @Test
    void multiStatementInputIsRejected() {
        // Two SELECTs in one input would let a driver that supports multi-statement
        // mode run them sequentially. Refuse any ; outside literals — even a trailing
        // empty statement.
        assertRejected("SELECT id FROM documents; SELECT * FROM users", "semicolon");
        assertRejected("SELECT id FROM documents;", "semicolon");
    }

    @Test
    void leadingWhitespaceAndCommentsBeforeSelectAreFine() {
        // The validator strips comments first, so a SELECT preceded by a comment is
        // still a SELECT for the leading-keyword check.
        assertAccepted("-- audit-trail tag\nSELECT id FROM documents");
        assertAccepted("/* preamble */ SELECT id FROM documents");
    }

    @Test
    void inputNotStartingWithSelectOrWithIsRejected() {
        // Even without a mutation keyword, anything not led by SELECT/WITH is refused.
        assertRejected("EXPLAIN SELECT id FROM documents", "must start with SELECT or WITH");
        assertRejected("SHOW TABLES", "must start with SELECT or WITH");
        assertRejected("DESCRIBE documents", "must start with SELECT or WITH");
        assertRejected("VALUES (1), (2)", "must start with SELECT or WITH");
    }

    @Test
    void dollarQuotedStringIsRejectedOutright() {
        // PostgreSQL dollar-quoted strings can hide arbitrary content from the literal
        // scanner. Refuse them; admins can use single-quoted literals if needed.
        assertRejected("SELECT $$DELETE FROM documents$$ FROM documents", "dollar-quoted");
        assertRejected("SELECT $tag$keyword DELETE inside$tag$ FROM documents", "dollar-quoted");
    }

    // ---------- input shape edge cases ----------

    @Test
    void nullAndBlankInputsAreRejected() {
        assertRejected(null, "required");
        assertRejected("", "required");
        assertRejected("   \t\n  ", "required");
    }

    @Test
    void unbalancedQuoteDoesNotCrashTheValidator() {
        // A malformed single-quote shouldn't throw — the worst case is the literal
        // scanner doesn't strip what the operator thought was a literal, so a DELETE
        // inside the unclosed quote gets caught by the keyword scan.
        final SqlReadOnlyValidator.Result r =
                SqlReadOnlyValidator.validate("SELECT 'oops, DELETE FROM users");
        assertFalse(r.ok());
        assertNotNull(r.error());
    }

    @Test
    void caseInsensitiveOnEveryRule() {
        // Each rule is case-insensitive. Spot-check the lowercased equivalents of the
        // mutation, leading-keyword, and multi-statement rejections.
        assertRejected("delete from documents", "DELETE");
        assertRejected("explain select id from documents", "must start with SELECT or WITH");
        assertRejected("select 1 ; select 2", "semicolon");
    }

    // ---------- MySQL / MariaDB write-via-SELECT primitives (finding #1) ----------

    @Test
    void rejectsSelectIntoOutfile() {
        // Canonical bypass: SELECT leads (passes), no listed mutation verb appears
        // (would have passed pre-fix), but INTO OUTFILE turns the SELECT into
        // arbitrary-file-write on the remote MySQL/MariaDB filesystem.
        assertRejected(
                "SELECT user, password_hash FROM mysql.user INTO OUTFILE '/var/lib/mysql-files/exfil.csv'",
                "OUTFILE");
    }

    @Test
    void rejectsSelectIntoDumpfile() {
        // DUMPFILE is the binary variant — paired with a hex literal payload it
        // becomes a primitive for dropping a shared object that CREATE FUNCTION
        // can then load. Full RCE chain on permissive MySQL configs.
        assertRejected(
                "SELECT 0x7f454c46 INTO DUMPFILE '/tmp/x.so'",
                "DUMPFILE");
    }

    @Test
    void rejectsLoadFileFunctionCall() {
        // Read-side mirror primitive — pulls a file from the DB server filesystem
        // into the result set. Same blast radius (secrets exfil) without needing
        // FILE-write privilege.
        assertRejected(
                "SELECT LOAD_FILE('/etc/passwd')",
                "LOAD_FILE");
    }

    @Test
    void rejectionIsCaseInsensitiveOnNewKeywords() {
        assertRejected("select id from t into outfile '/tmp/x.csv'", "OUTFILE");
        assertRejected("select 1 into dumpfile '/tmp/x'", "DUMPFILE");
        assertRejected("select load_file('/etc/hosts')", "LOAD_FILE");
    }

    // ---------- PostgreSQL file-read primitives (R2-F15) ----------

    @Test
    void rejectsPgReadFile() {
        // The PostgreSQL mirror of MySQL's LOAD_FILE. A "read-only" SELECT that
        // calls pg_read_file('/etc/passwd') exfiltrates files from the remote
        // DB server filesystem through the standard query path.
        assertRejected(
                "SELECT pg_read_file('/etc/passwd', 0, 10000) AS leak",
                "PG_READ_FILE");
    }

    @Test
    void rejectsPgReadBinaryFile() {
        // Binary variant — same primitive, different return type.
        assertRejected(
                "SELECT pg_read_binary_file('/etc/shadow')",
                "PG_READ_BINARY_FILE");
    }

    @Test
    void rejectsPgLsDir() {
        // Directory-enumeration primitive; reveals filesystem layout to a
        // caller with FILE-read privilege, prelude to a targeted pg_read_file.
        assertRejected(
                "SELECT * FROM pg_ls_dir('/etc')",
                "PG_LS_DIR");
    }

    @Test
    void rejectsPgStatFile() {
        // Stat metadata (size, mtime, owner). Lower-impact than the read
        // primitives but still server-side filesystem disclosure.
        assertRejected(
                "SELECT pg_stat_file('/etc/passwd')",
                "PG_STAT_FILE");
    }

    @Test
    void rejectsLoImport() {
        // Large-object import — reads a file from the DB server filesystem
        // into the database. The validator's "read-only" contract bans this
        // even though the data movement is technically just into Postgres.
        assertRejected(
                "SELECT lo_import('/etc/passwd')",
                "LO_IMPORT");
    }

    @Test
    void rejectsLoExport() {
        // Large-object export — writes a binary blob to the DB server
        // filesystem. Same write-primitive concern as INTO DUMPFILE.
        assertRejected(
                "SELECT lo_export(123, '/tmp/exfil.bin')",
                "LO_EXPORT");
    }

    @Test
    void postgresFileReadKeywordsAreCaseInsensitive() {
        assertRejected("select pg_read_file('/etc/passwd')", "PG_READ_FILE");
        assertRejected("select LO_EXPORT(1, '/tmp/x')", "LO_EXPORT");
    }

    @Test
    void cleanSelectMentioningOutfileInsideStringLiteralStillAccepted() {
        // The new keywords are whole-word matches, and the validator already
        // strips single-quoted string literals before the keyword scan. So a
        // label or alias literally containing "outfile" inside a string doesn't
        // false-positive. Confirms the fix didn't regress the happy path.
        assertAccepted("SELECT id, body FROM documents WHERE label = 'outfile-archive'");
        assertAccepted("WITH d AS (SELECT id FROM documents) SELECT * FROM d");
    }

    // ---------- :lastKey watermark placeholder ----------

    @Test
    void selectWithLastKeyPlaceholderIsAccepted() {
        // The canonical incremental-ingest shape. :lastKey is substituted with
        // a harmless empty string literal before the other safeguards run, so
        // the SELECT, the ORDER BY, and the COALESCE all parse cleanly.
        assertAccepted("SELECT body, id AS filename FROM documents "
                + "WHERE id > COALESCE(:lastKey::bigint, 0) ORDER BY id");
    }

    @Test
    void lastKeyDoesNotSmuggleMutatingKeywordsPastValidation() {
        // The validator's substitution replaces :lastKey with a single empty
        // string literal — it can't be used as a wedge for DELETE / multi-
        // statement injection. The two checks (mutating-keyword scan, then
        // semicolon refusal) BOTH catch these examples; either rejection is
        // a valid outcome.
        final SqlReadOnlyValidator.Result semi = SqlReadOnlyValidator.validate(
                "SELECT body FROM documents WHERE id > :lastKey; DELETE FROM users");
        assertFalse(semi.ok(), "semicolon+DELETE form must be rejected");
        assertNotNull(semi.error());
        // The mutating-keyword scan runs first in the validator's pipeline, so
        // DELETE is named in the error before the semicolon check fires. Accept
        // either signal as evidence the rule tripped.
        assertTrue(semi.error().toLowerCase().contains("delete")
                        || semi.error().toLowerCase().contains("semicolon"),
                "expected DELETE or semicolon rejection, got: " + semi.error());

        // UNION ALL DELETE — pure mutation-keyword case, no semicolon involved.
        assertRejected("SELECT body FROM documents WHERE id > :lastKey AND deleted_at IS NULL "
                + "UNION ALL DELETE FROM users", "DELETE");
    }

    @Test
    void multipleLastKeyOccurrencesAreAllSubstituted() {
        // The canonical shape only uses :lastKey once, but a query that
        // references it twice (e.g. inside both WHERE and a HAVING) must also
        // pass — perl-style substitution is global, not first-match.
        assertAccepted("SELECT body, id AS filename FROM documents "
                + "WHERE id > :lastKey AND priority > :lastKey ORDER BY id");
    }
}
