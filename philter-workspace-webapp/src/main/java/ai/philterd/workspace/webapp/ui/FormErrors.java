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
import ai.philterd.philter.model.exceptions.ErrorReasons;
import ai.philterd.philter.model.exceptions.PhilterError;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import ai.philterd.workspace.philter.PhilterClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What a form shows when Philter refuses a change: one message for the form and, when Philter names the
 * field it refused, one for that field. The messages are Philter Workspace's own, chosen by Philter's
 * status, {@code reason}, and {@code field}. Philter's message text is never shown or logged, since it can
 * repeat what was submitted, which may be personal information. Serializable, so it can be a flash
 * attribute in the session.
 *
 * @param message the form-level message
 * @param fields  a message for each refused field, by the field's name in Philter's API
 */
public record FormErrors(String message, Map<String, String> fields) implements Serializable {

    private static final Logger log = LoggerFactory.getLogger(FormErrors.class);

    /** Reasons Philter gives when the item changed after the form read it (a stale entity tag or revision). */
    static final Set<String> CHANGED = Set.of(ErrorReasons.CHANGED_CONCURRENTLY, ErrorReasons.POLICY_CHANGED,
            ErrorReasons.REDACT_LIST_CHANGED);

    static final String CHANGED_MESSAGE = "This was changed by someone else after you opened it. Reload it, "
            + "then make your change again.";
    static final String UNAVAILABLE_MESSAGE = "Philter could not complete the change. Try again, or check that "
            + "Philter is reachable.";
    static final String FIELD_MESSAGE = "Philter did not accept this value.";

    public FormErrors {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** A form-level message with no field messages. */
    public static FormErrors of(final String message) {
        return new FormErrors(message, Map.of());
    }

    /** The errors for {@code e}, with only the built-in messages. */
    public static FormErrors from(final Exception e) {
        return from(e, Map.of(), Map.of());
    }

    /**
     * The errors for {@code e}. {@code reasons} gives the form message for a Philter {@code reason}, such as
     * {@code policy_exists}, and {@code fieldMessages} the message for a refused field; both override the
     * built-in messages.
     */
    public static FormErrors from(final Exception e, final Map<String, String> reasons,
                                  final Map<String, String> fieldMessages) {
        if (e instanceof UnauthorizedException) {
            return of("Your Philter session has ended. Sign in again.");
        }
        if (!(e instanceof PhilterError error) || error.getStatusCode() == 0) {
            log.warn("A Philter call failed: {}", PhilterClients.describe(e));
            return of(UNAVAILABLE_MESSAGE);
        }
        final String reason = error.getReason();
        final String field = e instanceof ClientException client ? client.getField() : null;
        final Map<String, String> refusedFields = new LinkedHashMap<>();
        if (field != null && !field.isBlank()) {
            refusedFields.put(field, fieldMessages.getOrDefault(field, FIELD_MESSAGE));
        }
        if (reason != null && reasons.containsKey(reason)) {
            return new FormErrors(reasons.get(reason), refusedFields);
        }
        if (reason != null && CHANGED.contains(reason)) {
            return new FormErrors(CHANGED_MESSAGE, refusedFields);
        }
        final String message = switch (error.getStatusCode()) {
            case 400, 422 -> refusedFields.isEmpty() ? "Philter did not accept the values."
                    : "Philter did not accept the highlighted value.";
            case 403 -> "Your Philter account is not allowed to do this.";
            case 404 -> "Philter has no such item, or you cannot reach it.";
            case 409 -> "This conflicts with something that already exists in Philter.";
            case 412 -> CHANGED_MESSAGE;
            case 413 -> "This is larger than Philter accepts.";
            case 429 -> "Philter is busy. Wait a moment and try again.";
            default -> null;
        };
        if (message == null) {
            log.warn("A Philter call failed: {}", PhilterClients.describe(e));
            return new FormErrors(UNAVAILABLE_MESSAGE, refusedFields);
        }
        return new FormErrors(message, refusedFields);
    }

    /** The message for one field, or null. */
    public String field(final String name) {
        return fields.get(name);
    }

    public boolean has(final String name) {
        return fields.containsKey(name);
    }
}
