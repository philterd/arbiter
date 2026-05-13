/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.util;

/**
 * Helpers for emitting safe CSV cells from user-controlled data. The CSV exporters in
 * Arbiter — Audit Log, Second Opinions, etc. — serialize free-form strings (filenames,
 * batch names, comments, span text, JDBC URLs, SQL queries) into a downloadable
 * spreadsheet. Two distinct hazards have to be handled:
 *
 * <ol>
 *   <li><strong>RFC 4180 quoting</strong>: values containing {@code , " \r \n} must be
 *       wrapped in double quotes with embedded quotes doubled, or the spreadsheet
 *       parser splits the cell apart.</li>
 *   <li><strong>Formula injection</strong>: Excel and LibreOffice treat any cell whose
 *       first character is one of {@code = + - @ \t \r} as a formula. A reviewer
 *       commenting {@code =cmd|'/c calc'!A1} on a document plants RCE that fires when
 *       an admin opens the exported CSV. Prepending a single-quote forces the cell to
 *       be rendered as literal text.</li>
 * </ol>
 *
 * <p>This helper handles both. Use {@link #escapeField(String)} for every cell in every
 * CSV export — no exceptions, since even nominally-safe fields (timestamps, ids,
 * action codes) could be primed by a future feature change without anyone noticing.
 *
 * <p>References: OWASP "CSV Injection" guidance; CVE-2014-3524 (LibreOffice DDE).
 */
public final class Csv {

    private Csv() {
    }

    /**
     * Render a single CSV cell safely. Null and empty inputs return empty strings (which
     * RFC 4180 represents unquoted). Any other value is first guarded against formula
     * injection by prepending a single quote if its first character could be interpreted
     * as a formula start, then RFC 4180-quoted if it contains {@code , " \r \n}.
     *
     * <p>The leading single quote, when added, becomes part of the cell value and is
     * preserved through the quoting step — spreadsheets render it as the cell prefix
     * that signals literal-text mode, so the formula-trigger character that follows is
     * never evaluated.
     */
    public static String escapeField(final String value) {
        if (value == null || value.isEmpty()) return "";
        final char first = value.charAt(0);
        final boolean needsFormulaGuard = first == '='
                || first == '+'
                || first == '-'
                || first == '@'
                || first == '\t'
                || first == '\r';
        final String guarded = needsFormulaGuard ? "'" + value : value;
        final boolean needsQuoting = guarded.indexOf(',') >= 0
                || guarded.indexOf('"') >= 0
                || guarded.indexOf('\n') >= 0
                || guarded.indexOf('\r') >= 0;
        if (!needsQuoting) return guarded;
        return "\"" + guarded.replace("\"", "\"\"") + "\"";
    }
}
