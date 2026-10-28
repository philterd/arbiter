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

import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.philter.model.exceptions.ServiceUnavailableException;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormErrorsTest {

    /** What a Philter message might repeat back: text that was submitted. */
    private static final String SUBMITTED = "Jane Q. Example, 123-45-6789";

    private static ClientException refused(final int status, final String reason, final String field) {
        return new ClientException("HTTP " + status + ": " + SUBMITTED, status, SUBMITTED, reason, "req-1", field);
    }

    private static void assertNoPhilterText(final FormErrors errors) {
        assertFalse(errors.message().contains("Jane"), errors.message());
        errors.fields().values().forEach(m -> assertFalse(m.contains("Jane"), m));
    }

    @Test
    void aRefusedFieldIsNamedWithoutPhiltersText() {
        final FormErrors errors = FormErrors.from(refused(400, "invalid_request", "name"));
        assertEquals("Philter did not accept the highlighted value.", errors.message());
        assertTrue(errors.has("name"));
        assertEquals(FormErrors.FIELD_MESSAGE, errors.field("name"));
        assertNull(errors.field("description"));
        assertNoPhilterText(errors);
    }

    @Test
    void theCallersMessagesComeFirst() {
        final FormErrors errors = FormErrors.from(refused(409, "policy_exists", "name"),
                Map.of("policy_exists", "You already have a policy with that name."),
                Map.of("name", "Choose another name."));
        assertEquals("You already have a policy with that name.", errors.message());
        assertEquals("Choose another name.", errors.field("name"));
    }

    @Test
    void aChangeSinceTheFormWasReadAsksForAReload() {
        assertEquals(FormErrors.CHANGED_MESSAGE, FormErrors.from(refused(409, "policy_changed", null)).message());
        assertEquals(FormErrors.CHANGED_MESSAGE,
                FormErrors.from(refused(409, "changed_concurrently", null)).message());
        assertEquals(FormErrors.CHANGED_MESSAGE, FormErrors.from(refused(412, null, null)).message());
    }

    @Test
    void statusesHaveTheirOwnMessages() {
        assertEquals("Your Philter account is not allowed to do this.",
                FormErrors.from(refused(403, "missing_scope", null)).message());
        assertEquals("Philter has no such item, or you cannot reach it.",
                FormErrors.from(refused(404, "not_found", null)).message());
        assertEquals("Philter is busy. Wait a moment and try again.",
                FormErrors.from(refused(429, "rate_limited", null)).message());
        for (final int status : new int[]{400, 403, 404, 409, 413, 429, 500}) {
            assertNoPhilterText(FormErrors.from(refused(status, null, null)));
        }
    }

    @Test
    void anUnreachableOrFailingPhilterIsOneMessage() {
        assertEquals(FormErrors.UNAVAILABLE_MESSAGE, FormErrors.from(new IOException(SUBMITTED)).message());
        assertEquals(FormErrors.UNAVAILABLE_MESSAGE,
                FormErrors.from(new ServiceUnavailableException(SUBMITTED, SUBMITTED, null, null)).message());
        assertEquals(FormErrors.UNAVAILABLE_MESSAGE, FormErrors.from(refused(500, null, null)).message());
        assertTrue(FormErrors.from(new UnauthorizedException("expired")).message().contains("Sign in again"));
    }

    @Test
    void itCanBeAFlashAttributeInASharedSession() throws Exception {
        try (ObjectOutputStream out = new ObjectOutputStream(new ByteArrayOutputStream())) {
            out.writeObject(FormErrors.from(refused(400, null, "name")));
        }
    }

    @Test
    void jsonIsCheckedWithoutQuotingIt() {
        assertNull(JsonInput.objectError("{\"name\": \"x\"}"));
        assertEquals("Enter a JSON object.", JsonInput.objectError("  "));
        assertEquals("Enter a JSON object, starting with {.", JsonInput.objectError("[1, 2]"));
        assertTrue(JsonInput.objectError("{\"a\": 1} trailing").startsWith("This is not valid JSON"));
        assertTrue(JsonInput.objectError("{\"a\": 1} {\"b\": 2}").startsWith("This is not valid JSON"));
        final String broken = JsonInput.objectError("{\n  \"names\": [\"" + SUBMITTED + "\",\n}");
        assertTrue(broken.startsWith("This is not valid JSON: check line 3"), broken);
        assertFalse(broken.contains("Jane"), broken);
    }
}
