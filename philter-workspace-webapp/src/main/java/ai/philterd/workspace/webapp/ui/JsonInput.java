/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.ui;

import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks the text of a {@code fragments/forms :: json-editor} field before it is sent to Philter. The
 * message says where the problem is, never what the text there is, since a policy can hold personal
 * information (a list of names to redact, for example).
 */
public final class JsonInput {

    /** Refuses text after the object, as Philter and the browser's JSON.parse do. */
    private static final ObjectMapper MAPPER = tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private JsonInput() {
    }

    /** Why {@code text} is not a JSON object, or null when it is. */
    public static String objectError(final String text) {
        if (text == null || text.isBlank()) {
            return "Enter a JSON object.";
        }
        final JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (JacksonException e) {
            final TokenStreamLocation at = e.getLocation();
            return at == null || at.getLineNr() < 1
                    ? "This is not valid JSON."
                    : "This is not valid JSON: check line " + at.getLineNr() + ", column " + at.getColumnNr() + ".";
        }
        return node != null && node.isObject() ? null : "Enter a JSON object, starting with {.";
    }

    /** {@code text} indented for editing, or unchanged when it is not JSON. */
    public static String pretty(final String text) {
        if (text == null) {
            return "";
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(text));
        } catch (JacksonException e) {
            return text;
        }
    }

    /** Whether two texts are the same JSON, whatever their spacing. False when either is not JSON. */
    public static boolean same(final String a, final String b) {
        try {
            return a != null && b != null && MAPPER.readTree(a).equals(MAPPER.readTree(b));
        } catch (JacksonException e) {
            return false;
        }
    }
}
