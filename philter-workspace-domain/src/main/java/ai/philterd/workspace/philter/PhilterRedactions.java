/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.workspace.philter;

import ai.philterd.phileas.model.filtering.FilterType;
import ai.philterd.philter.PhilterClient;
import ai.philterd.philter.model.ExplainResponse;
import ai.philterd.philter.model.Span;
import ai.philterd.workspace.core.model.Redaction;
import ai.philterd.workspace.core.model.RedactionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Redacts text through Philter with the SDK's {@code explain} call, and turns Philter's answer into Philter
 * Workspace's {@link RedactionResponse}: the spans Philter applied, the redacted text with each span's position in
 * it, the policy and version Philter reports applying, and the document ID Philter recorded the redaction under.
 * Holds no client of its own; the caller passes the SDK client.
 */
public final class PhilterRedactions {

    private static final Logger log = LoggerFactory.getLogger(PhilterRedactions.class);

    private PhilterRedactions() {
    }

    /** Redacts {@code text} with {@code policyName} in {@code context}; a blank policy or context is not sent. */
    public static RedactionResponse redact(final PhilterClient client, final String text, final String policyName,
                                           final String context) throws IOException {
        final ExplainResponse explained;
        try {
            explained = client.explain(blankToNull(context), blankToNull(policyName), text);
        } catch (IOException | RuntimeException e) {
            // Philter's status and reason, never its message text, which can echo the request.
            final String detail = PhilterClients.describe(e);
            log.error("Philter explain call failed: {}", detail);
            throw new IOException("Philter explain call failed: " + detail);
        }
        return toResponse(text, explained);
    }

    static RedactionResponse toResponse(final String text, final ExplainResponse explained) {
        final List<Span> spans = new ArrayList<>(appliedSpans(explained));
        spans.sort(Comparator.comparingInt(Span::getCharacterStart));

        final List<Redaction> redactions = new ArrayList<>();
        final StringBuilder sb = new StringBuilder(text);
        int offset = 0;
        for (Span span : spans) {
            final String type = type(span);
            final String replacement = type.toUpperCase(Locale.ROOT);
            final int start = span.getCharacterStart();
            final int end = span.getCharacterEnd();

            final int startInFinal = start + offset;
            sb.replace(startInFinal, end + offset, replacement);

            final Redaction r = new Redaction();
            r.setId(UUID.randomUUID().toString());
            r.setText(span.getText());
            r.setStart(startInFinal);
            r.setEnd(startInFinal + replacement.length());
            r.setType(type);
            r.setConfidence(span.getConfidence());
            redactions.add(r);

            offset += replacement.length() - (end - start);
        }
        final RedactionResponse response = new RedactionResponse(text, sb.toString(), redactions);
        response.setPhilterDocumentId(explained == null ? null : explained.getDocumentId());
        recordAppliedPolicy(explained, response);
        return response;
    }

    /**
     * Copies the policy name and version Philter reports in the explain body ({@code policyName},
     * {@code policyVersion}), which the SDK's {@code ExplainResponse} does not map. Left unset when Philter does
     * not report them.
     */
    static void recordAppliedPolicy(final ExplainResponse explained, final RedactionResponse response) {
        if (explained == null || explained.getResponseBody() == null) {
            return;
        }
        try {
            final com.google.gson.JsonObject body =
                    com.google.gson.JsonParser.parseString(explained.getResponseBody()).getAsJsonObject();
            if (body.has("policyName") && body.get("policyName").isJsonPrimitive()) {
                response.setAppliedPolicyName(body.get("policyName").getAsString());
            }
            if (body.has("policyVersion") && body.get("policyVersion").isJsonPrimitive()) {
                response.setAppliedPolicyVersion(body.get("policyVersion").getAsInt());
            }
        } catch (RuntimeException e) {
            log.warn("Could not read the policy Philter applied from its response: {}", e.getClass().getSimpleName());
        }
    }

    private static List<Span> appliedSpans(final ExplainResponse response) {
        if (response == null || response.getExplanation() == null
                || response.getExplanation().getAppliedSpans() == null) {
            return List.of();
        }
        return response.getExplanation().getAppliedSpans();
    }

    /** Philter sends the enum name ({@code IP_ADDRESS}); Philter Workspace uses the Phileas type ({@code ip-address}). */
    static String type(final Span span) {
        final String name = span.getFilterType();
        if (name == null) {
            return FilterType.OTHER.getType();
        }
        try {
            return FilterType.valueOf(name).getType();
        } catch (IllegalArgumentException e) {
            return name.toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    private static String blankToNull(final String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
