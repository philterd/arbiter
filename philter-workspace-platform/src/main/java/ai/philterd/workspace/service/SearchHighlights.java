/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.service;

import java.util.UUID;

/**
 * Highlight snippets as safe HTML, for every search backend. A backend marks matched terms with per-request sentinels
 * that embed a fresh UUID, so document text cannot forge them; the snippet is then HTML-escaped as a whole, neutralizing
 * any markup in the document text, and only then are the sentinels swapped for {@code <mark>...</mark>}.
 */
public final class SearchHighlights {

    private SearchHighlights() {
    }

    /** A request's open and close sentinels. */
    public record Tags(String open, String close) {
        public static Tags fresh() {
            return new Tags("[[arbHL_" + UUID.randomUUID().toString().replace("-", "") + "_OPEN]]",
                    "[[arbHL_" + UUID.randomUUID().toString().replace("-", "") + "_CLOSE]]");
        }
    }

    public static String sanitize(final String raw, final String openTag, final String closeTag) {
        if (raw == null) return "";
        final String escaped = raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return escaped
                .replace(openTag, "<mark>")
                .replace(closeTag, "</mark>");
    }
}
