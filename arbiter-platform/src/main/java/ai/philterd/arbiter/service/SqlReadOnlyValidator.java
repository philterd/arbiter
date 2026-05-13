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

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Allow-list validator for the admin-supplied SQL on a relational data source. Data
 * sources are read-only by contract — they pull rows out of the remote DB into Arbiter's
 * redaction queue and never write back. The previous defence was a {@code DELETE |
 * TRUNCATE | DROP} keyword block-list which silently accepted {@code UPDATE},
 * {@code INSERT}, {@code MERGE}, {@code GRANT}, {@code REVOKE}, {@code EXEC},
 * {@code CALL}, {@code ALTER}, {@code CREATE}, and so on. This validator flips the
 * model: only {@code SELECT} and {@code WITH … SELECT} are accepted, multi-statement
 * input is refused, and a curated mutation-keyword list catches anything that slips
 * past the leading-keyword check.
 *
 * <p>The validator is a pure string check — it does not parse SQL and does not connect
 * to anything. It strips comments and single-quoted string literals before applying its
 * rules so that, for example, an inline comment containing the word {@code DELETE}
 * is accepted, but a real {@code DELETE} keyword anywhere in the executable text is
 * refused.
 *
 * <p>What gets accepted:
 * <ul>
 *   <li>{@code SELECT … FROM t}</li>
 *   <li>{@code SELECT t.a, COUNT(*) AS c FROM t WHERE … GROUP BY t.a HAVING c > 1
 *       ORDER BY t.a LIMIT 100}</li>
 *   <li>{@code WITH cte AS (SELECT id FROM t WHERE …) SELECT * FROM cte}</li>
 *   <li>{@code SELECT 'DELETE FROM users' AS demo FROM dual} — the keyword is inside
 *       a literal, so the validator ignores it.</li>
 * </ul>
 *
 * <p>What gets rejected:
 * <ul>
 *   <li>Anything starting with anything other than {@code SELECT} or {@code WITH}.</li>
 *   <li>Multi-statement input — any {@code ;} outside a literal.</li>
 *   <li>Any whole-word occurrence of a mutation/DDL/DCL keyword anywhere in the
 *       executable text. Whole-word matching means column names like
 *       {@code dropoff_count} are still fine.</li>
 *   <li>Dollar-quoted strings ({@code $$…$$}) — they are too easy to use as a hiding
 *       place for a mutating keyword and rare enough in admin queries to refuse
 *       outright.</li>
 *   <li>Null, blank, or empty input.</li>
 * </ul>
 */
public final class SqlReadOnlyValidator {

    /**
     * Comment-stripper. {@code /* … *​/} block comments may span multiple lines; the
     * line forms ({@code --} and {@code #}) run to end-of-line. {@code (?s)} keeps the
     * dot multiline-safe for the block form.
     */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)(--|#)[^\\r\\n]*");

    /**
     * Standard SQL single-quoted string literal, including doubled-quote escaping
     * ({@code 'O''Brien'}). Whitespace between the closing quote and the next token is
     * preserved by the surrounding text.
     */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    /** PostgreSQL-style dollar-quoted body ({@code $$ … $$} or {@code $tag$ … $tag$}). */
    private static final Pattern DOLLAR_QUOTE = Pattern.compile("\\$[a-zA-Z0-9_]*\\$");

    /**
     * Whole-word mutation / DDL / DCL keywords. Any of these in the executable text
     * is grounds for refusal — even when paired with a leading {@code SELECT}, since
     * a {@code WITH cte AS (DELETE …) SELECT *} chain in PostgreSQL is in fact a
     * mutating statement.
     */
    private static final Pattern MUTATION_KEYWORDS = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|TRUNCATE|DROP|MERGE|REPLACE|GRANT|REVOKE"
                    + "|EXEC|EXECUTE|CALL|ALTER|CREATE|RENAME|LOCK|UNLOCK|SET|DECLARE"
                    + "|BEGIN|COMMIT|ROLLBACK|SAVEPOINT|VACUUM|ANALYZE|CLUSTER|COPY"
                    + "|REINDEX|REFRESH|LISTEN|NOTIFY|UNLISTEN|DISCARD|RESET|LOAD"
                    + "|PREPARE|DEALLOCATE)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Leading-keyword check: the first significant token must be SELECT or WITH. */
    private static final Pattern LEADING_KEYWORD = Pattern.compile(
            "^\\s*(SELECT|WITH)\\b", Pattern.CASE_INSENSITIVE);

    private SqlReadOnlyValidator() {
    }

    /** Outcome record. {@link #error()} is null when {@link #ok()} is true. */
    public record Result(boolean ok, String error) {
        public static Result accepted() { return new Result(true, null); }
        public static Result rejected(final String message) { return new Result(false, message); }
    }

    /**
     * Inspect a SQL string and return whether it is safe to store as a read-only
     * data-source query. Returns the most specific applicable error on rejection.
     */
    public static Result validate(final String sql) {
        if (sql == null || sql.isBlank()) {
            return Result.rejected("SQL query is required.");
        }
        // Dollar-quoted strings can hide mutating keywords from the substring scan
        // below. We don't parse them; we refuse them.
        if (DOLLAR_QUOTE.matcher(sql).find()) {
            return Result.rejected("Dollar-quoted strings ($$…$$) are not allowed in data-source "
                    + "queries. Use single-quoted string literals.");
        }

        // Strip block + line comments, then single-quoted string literals. After this
        // the remaining text is executable SQL only — any keyword we see here is
        // genuinely intended to run on the remote database.
        String stripped = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        stripped = LINE_COMMENT.matcher(stripped).replaceAll(" ");
        stripped = STRING_LITERAL.matcher(stripped).replaceAll("''");

        // Mutation-keyword scan runs first so the rejection message names the specific
        // verb the admin used (more useful than the generic "must start with SELECT"),
        // and so a query like `WITH d AS (DELETE …) SELECT * FROM d` is caught by the
        // DELETE inside the CTE rather than slipping past on the WITH prefix.
        final java.util.regex.Matcher m = MUTATION_KEYWORDS.matcher(stripped);
        if (m.find()) {
            return Result.rejected("SQL query contains a disallowed keyword \""
                    + m.group(1).toUpperCase(Locale.ROOT)
                    + "\". Data sources must use read-only queries (SELECT / WITH … SELECT).");
        }

        // Multi-statement support is a common privilege-escalation vector. Refuse any
        // ; outside literals — even a trailing semicolon, since some drivers accept it
        // as a second empty statement.
        if (stripped.indexOf(';') >= 0) {
            return Result.rejected("SQL query must contain a single statement. Remove the "
                    + "semicolon(s) and any trailing statements.");
        }

        if (!LEADING_KEYWORD.matcher(stripped).find()) {
            return Result.rejected("SQL query must start with SELECT or WITH. Data sources "
                    + "must be read-only.");
        }

        return Result.accepted();
    }
}
