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

import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.Location;
import ai.philterd.arbiter.model.Span;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders a single APPROVED document plus its APPROVED spans as a BIO-format
 * sequence of token / label lines, the standard input shape for sequence-tag
 * NER training.
 *
 * <p>Output schema (one document per file, UTF-8):
 * <pre>{@code
 * The    O
 * patient,    O
 * John    B-PERSON
 * Doe,    B-PERSON
 * was    O
 * admitted    O
 * on    O
 * 2023-10-12    B-DATE
 * to    O
 * Mayo    B-ORG
 * Clinic.    B-ORG
 *
 * Contact    O
 * ...
 * }</pre>
 *
 * <p>Tokenization is whitespace-split and preserves the source's paragraph
 * structure — any run of whitespace containing two or more newlines becomes
 * a blank line in the output, separating one paragraph from the next exactly
 * the way most BIO loaders expect (blank line == sentence/document break).
 *
 * <p>Per-token labels: every token whose character range falls inside an
 * APPROVED span is emitted as {@code B-<TYPE>} (using the span's type
 * uppercased and whitespace-normalised); tokens outside any approved span
 * are emitted as {@code O}.
 */
@Service
public class BioExportRenderer {

    /** Span status that opts a span into the export. */
    public static final String APPROVED_STATUS = "APPROVED";

    private static final char SEPARATOR = '\t';

    /**
     * Render the given document's text and approved spans as a BIO file body.
     * Returns the empty string if the document has no original text.
     */
    public String render(final Document document, final List<Span> spans) {
        final String text = document.getOriginalText();
        if (text == null || text.isEmpty()) return "";

        // Approved, located spans only — sorted by start offset so the
        // labelFor lookup can short-circuit once it passes the cursor.
        final List<Span> approved = new ArrayList<>();
        if (spans != null) {
            for (Span s : spans) {
                if (!APPROVED_STATUS.equals(s.getStatus())) continue;
                if (s.getLocation() == null) continue;
                approved.add(s);
            }
        }
        approved.sort(Comparator.comparingInt(s -> s.getLocation().characterStart()));

        final StringBuilder out = new StringBuilder(Math.max(64, text.length()));
        final int n = text.length();
        int i = 0;
        while (i < n) {
            // Consume whitespace; collapse runs but preserve paragraph breaks
            // (>= 2 newlines in the run produces a blank line in output).
            if (Character.isWhitespace(text.charAt(i))) {
                int newlines = 0;
                while (i < n && Character.isWhitespace(text.charAt(i))) {
                    if (text.charAt(i) == '\n') newlines++;
                    i++;
                }
                if (newlines >= 2 && out.length() > 0) {
                    out.append('\n');
                }
                continue;
            }
            // Non-whitespace token.
            final int start = i;
            while (i < n && !Character.isWhitespace(text.charAt(i))) i++;
            final int end = i;
            final String token = text.substring(start, end);
            final String label = labelFor(start, end, approved);
            out.append(token).append(SEPARATOR).append(label).append('\n');
        }
        return out.toString();
    }

    /**
     * Returns {@code B-<TYPE>} when the token range overlaps any approved span,
     * {@code O} otherwise. Overlap is "any character of the token falls inside
     * the span's character range" — so a punctuation-attached token like
     * {@code Doe,} is still tagged when the span covers {@code Doe} but the
     * comma is outside it.
     */
    private static String labelFor(final int tokenStart, final int tokenEnd,
                                   final List<Span> approved) {
        for (Span s : approved) {
            final Location loc = s.getLocation();
            final int spanStart = loc.characterStart();
            final int spanEnd = loc.characterEnd();
            if (spanEnd <= tokenStart) continue;     // span ends before token — keep scanning
            if (spanStart >= tokenEnd) break;         // sorted by start; nothing further can overlap
            // Overlap.
            return "B-" + normalizeType(s.getType());
        }
        return "O";
    }

    /**
     * Lift the span's type name into a stable label suffix: uppercase, with
     * any non-alphanumeric character collapsed into a single underscore. The
     * result is a single token without whitespace, suitable as a NER label.
     */
    private static String normalizeType(final String type) {
        if (type == null || type.isBlank()) return "MISC";
        final StringBuilder sb = new StringBuilder(type.length());
        boolean lastWasSeparator = false;
        for (int i = 0; i < type.length(); i++) {
            final char c = type.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(Character.toUpperCase(c));
                lastWasSeparator = false;
            } else if (!lastWasSeparator && sb.length() > 0) {
                sb.append('_');
                lastWasSeparator = true;
            }
        }
        if (sb.length() == 0) return "MISC";
        if (sb.charAt(sb.length() - 1) == '_') sb.setLength(sb.length() - 1);
        return sb.toString().toUpperCase(Locale.ROOT);
    }
}
