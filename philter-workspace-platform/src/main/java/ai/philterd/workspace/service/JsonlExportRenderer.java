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

import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.Span;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders an APPROVED document plus its APPROVED spans as a single JSONL line for
 * downstream training/annotation pipelines.
 *
 * <p>Output schema (one object per line, newline-separated, UTF-8):
 * <pre>{@code
 * {
 *   "text": "<original document text>",
 *   "entities": [
 *     {"start": <inclusive char offset>, "end": <exclusive char offset>,
 *      "label": "<span type>", "text": "<span text>",
 *      "decidedBy": "person" | "threshold"}, ...
 *   ],
 *   "metadata": {
 *     "doc_id": "<document id>",
 *     "annotator": "<email of first reviewer to approve>",
 *     "confidence": <average confidence across approved spans, 0..1>
 *   }
 * }
 * }</pre>
 *
 * <p>{@code decidedBy} is {@code "person"} when a person added the span or set its status, and
 * {@code "threshold"} when it was accepted at ingest by the batch's PII Threshold and never decided
 * by a person (see {@link Span#decidedByPerson()}). It does not name the person.
 */
@Service
public class JsonlExportRenderer {

    /** Span status that opts a span into the export. */
    public static final String APPROVED_STATUS = "APPROVED";

    public static final String DECIDED_BY_PERSON = "person";
    public static final String DECIDED_BY_THRESHOLD = "threshold";

    private final ObjectMapper objectMapper;

    public JsonlExportRenderer() {
        // A dedicated mapper so a globally-customised Jackson config can't change
        // the on-the-wire shape — exports are a stable contract for downstream
        // consumers.
        this.objectMapper = tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults()
                .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    /**
     * Render one document as a JSONL line (without the trailing newline).
     * Caller is responsible for newline framing — this matches the way streaming
     * writers typically batch lines with a single {@code write("\n")} between them.
     *
     * @param document the source document; only {@code originalText}, {@code id},
     *                 and {@code approvedBy} are read.
     * @param spans    every span attached to the document — non-APPROVED ones are
     *                 filtered out by this method, callers don't have to pre-filter.
     * @return a single-line UTF-8 JSON string, no embedded {@code '\n'}.
     */
    public String render(final Document document, final List<Span> spans) throws JacksonException {
        final List<Map<String, Object>> entities = new ArrayList<>();
        double confidenceSum = 0.0;
        int confidenceCount = 0;
        if (spans != null) {
            for (Span s : spans) {
                if (!APPROVED_STATUS.equals(s.getStatus())) continue;
                if (s.getLocation() == null) continue;
                final Map<String, Object> entity = new java.util.LinkedHashMap<>();
                entity.put("start", s.getLocation().characterStart());
                entity.put("end", s.getLocation().characterEnd());
                entity.put("label", s.getType() == null ? "" : s.getType());
                entity.put("text", s.getText() == null ? "" : s.getText());
                entity.put("decidedBy", s.decidedByPerson() ? DECIDED_BY_PERSON : DECIDED_BY_THRESHOLD);
                entities.add(entity);
                confidenceSum += s.getConfidence();
                confidenceCount++;
            }
        }

        final Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("doc_id", document.getId() == null ? "" : document.getId());
        metadata.put("annotator", primaryAnnotator(document));
        if (confidenceCount > 0) {
            // Round to 4 decimal places so the value reads cleanly in JSONL viewers
            // and survives a re-serialization round-trip without floating-point noise.
            final double avg = confidenceSum / confidenceCount;
            metadata.put("confidence", Math.round(avg * 10000.0) / 10000.0);
        }

        final Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("text", document.getOriginalText() == null ? "" : document.getOriginalText());
        root.put("entities", entities);
        root.put("metadata", metadata);

        // ObjectMapper.writeValueAsString never emits a trailing newline — the
        // streaming writer adds it. JSON's "\n" within strings is escaped, so the
        // output is guaranteed to be a single line.
        return objectMapper.writeValueAsString(root);
    }

    private static String primaryAnnotator(final Document document) {
        final List<String> approvers = document.getApprovedBy();
        if (approvers == null || approvers.isEmpty()) return "";
        // First approver is the primary annotator; subsequent approvers in dual-review
        // setups are validators rather than the original annotator.
        final String first = approvers.get(0);
        return first == null ? "" : first;
    }
}
