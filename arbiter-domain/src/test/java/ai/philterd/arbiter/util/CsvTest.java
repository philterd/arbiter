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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Csv#escapeField(String)} — the shared CSV cell escaper used by
 * both the Audit Log and Second Opinions exports. Pins two security properties:
 *
 * <ul>
 *   <li>Values whose first character could be interpreted by a spreadsheet as a formula
 *       start ({@code = + - @ \t \r}) get a literal-text guard prefix so the cell never
 *       evaluates as a formula when opened in Excel / LibreOffice / Numbers.</li>
 *   <li>RFC 4180 quoting still works on all the standard escapes the previous helper
 *       handled ({@code , " \r \n}), so existing exports remain machine-readable.</li>
 * </ul>
 */
class CsvTest {

    // ---------- formula-injection guard ----------

    @Test
    void prependsSingleQuoteForLeadingEquals() {
        // The classic CSV-injection payload. After escaping, the cell starts with a
        // single quote — spreadsheets interpret that as "render this cell as text" so
        // the =cmd|... formula never evaluates.
        final String safe = Csv.escapeField("=cmd|'/c calc'!A1");
        assertTrue(safe.startsWith("'="),
                "missing leading single-quote guard: " + safe);
        assertFalse(safe.startsWith("="),
                "leaked formula trigger to the spreadsheet: " + safe);
    }

    @Test
    void prependsSingleQuoteForLeadingPlus() {
        assertEquals("'+1+1", Csv.escapeField("+1+1"));
    }

    @Test
    void prependsSingleQuoteForLeadingMinus() {
        // Minus is a formula-trigger character too — `-2+5` evaluates as 3 in Excel.
        assertEquals("'-2+5", Csv.escapeField("-2+5"));
    }

    @Test
    void prependsSingleQuoteForLeadingAt() {
        // @SUM(...) is a legacy spreadsheet syntax; still honored by recent Excel
        // versions to keep .xls compatibility, so it's a formula trigger.
        assertEquals("'@SUM(A1)", Csv.escapeField("@SUM(A1)"));
    }

    @Test
    void prependsSingleQuoteForLeadingTab() {
        // Tab and CR are documented formula-injection vectors — Excel strips the
        // leading whitespace before evaluating the rest of the cell.
        assertEquals("'\t=1+1", Csv.escapeField("\t=1+1"));
    }

    @Test
    void prependsSingleQuoteForLeadingCarriageReturn() {
        // CR triggers the same Excel pre-strip path. The full cell also contains \r,
        // so the standard quoting step wraps the result in double quotes.
        final String result = Csv.escapeField("\r=1+1");
        assertTrue(result.startsWith("\"'\r="),
                "expected quoted cell with leading guard, got: " + result);
    }

    @Test
    void doesNotPrependGuardWhenFormulaCharIsNotFirst() {
        // The danger is *only* at the first position; embedded = + - @ inside text
        // are inert and must not be mangled.
        assertEquals("name = bob", Csv.escapeField("name = bob"));
        assertEquals("contact: jeff@example.com",
                Csv.escapeField("contact: jeff@example.com"));
        assertEquals("a+b-c", Csv.escapeField("a+b-c"));
    }

    @Test
    void plainTextPassesThroughUnchanged() {
        assertEquals("hello world", Csv.escapeField("hello world"));
        assertEquals("Document 42 approved", Csv.escapeField("Document 42 approved"));
    }

    // ---------- RFC 4180 quoting still works ----------

    @Test
    void quotesValueWithComma() {
        assertEquals("\"alice, bob\"", Csv.escapeField("alice, bob"));
    }

    @Test
    void quotesValueWithEmbeddedDoubleQuote() {
        assertEquals("\"she said \"\"hi\"\"\"", Csv.escapeField("she said \"hi\""));
    }

    @Test
    void quotesValueWithEmbeddedNewline() {
        assertEquals("\"line1\nline2\"", Csv.escapeField("line1\nline2"));
    }

    @Test
    void quotesValueWithEmbeddedCarriageReturn() {
        // Embedded CR (not leading) — needs quoting but no formula guard.
        assertEquals("\"line1\rline2\"", Csv.escapeField("line1\rline2"));
    }

    // ---------- formula guard + quoting compose correctly ----------

    @Test
    void leadingFormulaCharThatAlsoNeedsQuotingIsBothGuardedAndQuoted() {
        // The cell starts with = (formula trigger) AND contains a comma (quote
        // trigger). After escaping, the cell must be wrapped in quotes with the
        // leading single-quote intact inside the quoted region — that's what
        // spreadsheets interpret as a literal-text marker.
        final String safe = Csv.escapeField("=SUM(A1,B1)");
        assertEquals("\"'=SUM(A1,B1)\"", safe);
    }

    @Test
    void embeddedDoubleQuoteIsDoubledEvenWithFormulaGuard() {
        // =sum("a","b") — the quotes still have to be doubled inside the cell,
        // AND the leading = needs the formula guard.
        final String safe = Csv.escapeField("=sum(\"a\",\"b\")");
        assertEquals("\"'=sum(\"\"a\"\",\"\"b\"\")\"", safe);
    }

    // ---------- edge cases ----------

    @Test
    void nullReturnsEmptyString() {
        assertEquals("", Csv.escapeField(null));
    }

    @Test
    void emptyStringReturnsEmptyString() {
        assertEquals("", Csv.escapeField(""));
    }

    @Test
    void singleQuoteAtStartIsNotItselfAFormulaTrigger() {
        // ' is the literal-text marker, not a formula trigger. The helper must not
        // double-prefix or otherwise mangle a value that already begins with one.
        assertEquals("'already-quoted", Csv.escapeField("'already-quoted"));
    }

    @Test
    void wholeFormulaPayloadEndToEnd() {
        // OWASP's canonical CSV-injection payload — DDE invocation that runs an
        // arbitrary command on Windows when the cell is evaluated. After escaping,
        // the cell renders as literal text and nothing executes.
        final String payload = "=2+5+cmd|' /C calc'!A0";
        final String safe = Csv.escapeField(payload);
        // Cell must start with ' (literal-text), and must NOT start with = (formula).
        assertTrue(safe.startsWith("'"),
                "no formula guard applied: " + safe);
        assertFalse(safe.startsWith("="),
                "raw formula trigger leaked: " + safe);
        // The payload contains no comma / quote / newline, so no RFC 4180 wrapping
        // is needed — the helper should return the guarded value verbatim.
        assertEquals("'" + payload, safe);
    }
}
