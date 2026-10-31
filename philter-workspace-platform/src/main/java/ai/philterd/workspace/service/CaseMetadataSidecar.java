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

import ai.philterd.workspace.model.CaseMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

/**
 * A file's case metadata (#16) in a sidecar file beside it, for file-based data sources: {@code motion.pdf} has its
 * metadata in {@code motion.pdf.case.json}. Sidecars are never imported as documents themselves.
 *
 * <pre>{"caseNumber": "1:26-cv-01234", "caseType": "civil", "filer": "Jane Roe",
 *  "feeStatus": "paid", "ifp": false}</pre>
 */
public final class CaseMetadataSidecar {

    public static final String SUFFIX = ".case.json";
    /** A sidecar is small; anything larger is not one. */
    static final int MAX_BYTES = 16 * 1024;
    private static final Set<String> FIELDS = Set.of("caseNumber", "caseType", "filer", "feeStatus", "ifp");
    private static final ObjectMapper JSON = new ObjectMapper();

    private CaseMetadataSidecar() {
    }

    /** Whether the name is a sidecar's, which is never a document. */
    public static boolean isSidecar(final String name) {
        return name != null && name.endsWith(SUFFIX);
    }

    /** The sidecar name for a document's name. */
    public static String sidecarFor(final String name) {
        return name + SUFFIX;
    }

    /**
     * Reads a sidecar.
     *
     * @return the metadata, or null when the sidecar gives none
     * @throws InvalidSidecarException with a message for the import log, which never quotes the sidecar's values
     */
    public static CaseMetadata parse(final byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) {
            throw new InvalidSidecarException("Its case sidecar is larger than " + MAX_BYTES + " bytes.");
        }
        final JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (IOException e) {
            throw new InvalidSidecarException("Its case sidecar is not valid JSON.");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidSidecarException("Its case sidecar is not a JSON object.");
        }
        for (Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
            final String name = names.next();
            if (!FIELDS.contains(name)) {
                throw new InvalidSidecarException("Its case sidecar has an unknown field \"" + name + "\".");
            }
        }
        final JsonNode ifp = root.get("ifp");
        if (ifp != null && !ifp.isNull() && !ifp.isBoolean()) {
            throw new InvalidSidecarException("Its case sidecar's ifp must be true or false.");
        }
        try {
            return CaseMetadata.of(text(root, "caseNumber"), text(root, "caseType"), text(root, "filer"),
                    text(root, "feeStatus"), ifp == null || ifp.isNull() ? null : ifp.asBoolean());
        } catch (CaseMetadata.InvalidException e) {
            throw new InvalidSidecarException("Its case sidecar is invalid: " + e.getMessage());
        }
    }

    private static String text(final JsonNode root, final String field) {
        final JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new InvalidSidecarException("Its case sidecar's " + field + " must be a string.");
        }
        return node.asText();
    }

    /** A sidecar that cannot be used; the document is not imported. */
    public static class InvalidSidecarException extends IllegalArgumentException {
        public InvalidSidecarException(final String message) {
            super(message);
        }
    }
}
