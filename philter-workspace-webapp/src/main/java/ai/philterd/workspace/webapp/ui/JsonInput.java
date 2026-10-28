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

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Checks the text of a {@code fragments/forms :: json-editor} field before it is sent to Philter. The
 * message says where the problem is, never what the text there is, since a policy can hold personal
 * information (a list of names to redact, for example).
 */
public final class JsonInput {

    /** Refuses text after the object, as Philter and the browser's JSON.parse do. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

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
        } catch (JsonProcessingException e) {
            final JsonLocation at = e.getLocation();
            return at == null || at.getLineNr() < 1
                    ? "This is not valid JSON."
                    : "This is not valid JSON: check line " + at.getLineNr() + ", column " + at.getColumnNr() + ".";
        }
        return node != null && node.isObject() ? null : "Enter a JSON object, starting with {.";
    }
}
