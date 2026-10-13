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
package ai.philterd.arbiter.philter;

import ai.philterd.arbiter.core.model.Redaction;
import ai.philterd.arbiter.core.model.RedactionResponse;
import ai.philterd.philter.model.ExplainResponse;
import ai.philterd.philter.model.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link PhilterClient} backed by the Philter SDK, for remote Philter instances.
 */
public class PhilterClientImpl implements PhilterClient {

    private static final Logger log = LoggerFactory.getLogger(PhilterClientImpl.class);

    private final ai.philterd.philter.PhilterClient client;
    private final String policyName;

    public PhilterClientImpl(final ai.philterd.philter.PhilterClient client, final String policyName) {
        this.client = client;
        this.policyName = blankToNull(policyName);
    }

    @Override
    public RedactionResponse redactPdf(final byte[] pdfBytes, final String context) throws IOException {
        // TODO: Philter's PDF endpoints return a redacted PDF but no spans, so there is nothing
        // to review. Pairs with the TODO in RedactionServiceImpl.getRedactedPdf.
        throw new IOException("PDF review is not supported with a remote Philter instance; "
                + "use Embedded Philter for this batch.");
    }

    @Override
    public Map<String, Object> explain(final String text, final String context) throws IOException {
        final List<Map<String, Object>> explanation = new ArrayList<>();
        for (Span span : appliedSpans(callExplain(text, context))) {
            final Map<String, Object> map = new HashMap<>();
            map.put("text", span.getText());
            map.put("type", span.getFilterType());
            map.put("characterStart", span.getCharacterStart());
            map.put("characterEnd", span.getCharacterEnd());
            map.put("confidence", span.getConfidence());
            explanation.add(map);
        }
        final Map<String, Object> result = new HashMap<>();
        result.put("explanation", explanation);
        return result;
    }

    @Override
    public String redact(final String text, final String context, final List<Redaction> approvedSpans) {
        // The reviewers have already decided what to redact, so apply their spans here rather
        // than asking Philter to detect again.
        return ApprovedSpans.apply(text, approvedSpans);
    }

    @Override
    public RedactionResponse redact(final String text, final String context) throws IOException {
        final List<Span> spans = new ArrayList<>(appliedSpans(callExplain(text, context)));
        spans.sort(Comparator.comparingInt(Span::getCharacterStart));

        final List<Redaction> redactions = new ArrayList<>();
        final StringBuilder sb = new StringBuilder(text);
        int offset = 0;
        for (Span span : spans) {
            final String type = span.getFilterType();
            final String replacement = type.toUpperCase();
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
        return new RedactionResponse(text, sb.toString(), redactions);
    }

    private ExplainResponse callExplain(final String text, final String context) throws IOException {
        try {
            return client.explain(blankToNull(context), policyName, text);
        } catch (IOException | RuntimeException e) {
            final String detail = PhilterClients.describe(e);
            log.error("Philter explain call failed: {}", detail);
            throw new IOException("Philter explain call failed: " + detail);
        }
    }

    private static List<Span> appliedSpans(final ExplainResponse response) {
        if (response == null || response.getExplanation() == null
                || response.getExplanation().getAppliedSpans() == null) {
            return List.of();
        }
        return response.getExplanation().getAppliedSpans();
    }

    private static String blankToNull(final String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
