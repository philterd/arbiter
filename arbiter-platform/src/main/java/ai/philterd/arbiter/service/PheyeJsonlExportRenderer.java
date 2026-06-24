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
import ai.philterd.arbiter.model.Span;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders an APPROVED document plus its APPROVED spans as a single JSONL line in the PhEye
 * model-training corpus shape (parity with {@code ph-eye-model-training} {@code passages.jsonl}),
 * so an export is drop-in usable as training and evaluation data for the PhEye PII models.
 *
 * <p>Output schema (one object per line, newline-separated, UTF-8):
 * <pre>{@code
 * {"text": "<original document text>",
 *  "spans": [{"start": <inclusive char offset>, "end": <exclusive char offset>, "label": "<span type>"}, ...]}
 * }</pre>
 *
 * <p>A document with no approved spans is still rendered, with an empty {@code spans} array, so
 * reviewed negatives are retained.
 */
@Service
public class PheyeJsonlExportRenderer {

    /** Span status that opts a span into the export. */
    public static final String APPROVED_STATUS = "APPROVED";

    private final ObjectMapper objectMapper;

    public PheyeJsonlExportRenderer() {
        // A dedicated mapper so a globally-customised Jackson config can't change the on-the-wire
        // shape; the export is a stable contract for the training pipeline.
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Render one document as a JSONL line (without the trailing newline; the streaming writer adds
     * it). Only APPROVED spans with a location are emitted; the caller does not have to pre-filter.
     *
     * @param document the source document; {@code originalText} is the {@code text}.
     * @param spans    every span attached to the document.
     * @return a single-line UTF-8 JSON string with no embedded {@code '\n'}.
     */
    public String render(final Document document, final List<Span> spans) throws JsonProcessingException {
        final List<Map<String, Object>> spanObjects = new ArrayList<>();
        if (spans != null) {
            for (final Span s : spans) {
                if (!APPROVED_STATUS.equals(s.getStatus())) continue;
                if (s.getLocation() == null) continue;
                final Map<String, Object> span = new LinkedHashMap<>();
                span.put("start", s.getLocation().characterStart());
                span.put("end", s.getLocation().characterEnd());
                span.put("label", s.getType() == null ? "" : s.getType());
                spanObjects.add(span);
            }
        }

        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("text", document.getOriginalText() == null ? "" : document.getOriginalText());
        root.put("spans", spanObjects);

        // writeValueAsString never emits a trailing newline, and any '\n' inside text is escaped, so
        // the result is guaranteed to be a single JSONL line.
        return objectMapper.writeValueAsString(root);
    }
}
