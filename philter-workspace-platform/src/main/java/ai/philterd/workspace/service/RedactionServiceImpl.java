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
package ai.philterd.workspace.service;

import ai.philterd.workspace.core.model.Redaction;
import ai.philterd.workspace.core.model.RedactionResponse;
import ai.philterd.workspace.philter.PhilterClient;
import ai.philterd.workspace.philter.PhilterClientFactory;
import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.services.filters.filtering.PdfFilterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Service
public class RedactionServiceImpl implements RedactionService {

    private static final Logger log = LoggerFactory.getLogger(RedactionServiceImpl.class);

    private final PhilterClientFactory philterClientFactory;
    private final PhilterSettings philter;

    public RedactionServiceImpl(final PhilterClientFactory philterClientFactory,
                                final PhilterSettings philter) {
        this.philterClientFactory = philterClientFactory;
        this.philter = philter;
    }

    private PhilterClient client(final String policyName) {
        return philterClientFactory.create(philter.url(), philter.apiKey(), policyName);
    }

    @Override
    public RedactionResponse redactText(final String text, final String policyName,
                                        final String context) throws IOException {
        return client(policyName).redact(text, context == null ? "" : context);
    }

    @Override
    public RedactionResponse redactPdf(final InputStream inputStream, final String policyName,
                                       final String context) throws IOException {
        final byte[] bytes = inputStream.readAllBytes();
        return client(policyName).redactPdf(bytes, context == null ? "" : context);
    }

    /**
     * Applies the reviewed spans to the PDF with the Phileas library. Refuses, rather than return the
     * original, when a span has no position on the page to draw its box at.
     */
    @Override
    public byte[] getRedactedPdf(final InputStream originalPdf,
                                 final RedactionResponse redactionResponse) throws IOException {
        final byte[] pdfBytes = originalPdf.readAllBytes();
        final List<Redaction> redactions = redactionResponse == null || redactionResponse.getRedactions() == null
                ? List.of() : redactionResponse.getRedactions();

        final List<Span> spans = new ArrayList<>();
        for (Redaction r : redactions) {
            if (!hasCoordinates(r)) {
                throw new IOException("A redaction has no position in the PDF, so a redacted PDF cannot be "
                        + "produced. PDF redaction through Philter is not yet available.");
            }
            final Span span = new Span();
            span.setText(r.getText());
            span.setReplacement(r.getType().toUpperCase());
            span.setPageNumber(r.getPageNumber());
            span.setLowerLeftX(r.getLowerLeftX());
            span.setLowerLeftY(r.getLowerLeftY());
            span.setUpperRightX(r.getUpperRightX());
            span.setUpperRightY(r.getUpperRightY());
            span.setApplied(true);
            spans.add(span);
        }

        try {
            final PdfFilterService filterService = new PdfFilterService(
                    new PhileasConfiguration(new Properties()), null, null, null);
            // apply() does not read the policy, but needs one.
            return filterService.apply(new Policy(), pdfBytes, spans, MimeType.APPLICATION_PDF);
        } catch (Exception e) {
            log.error("Failed to apply redactions to PDF via Phileas: {}", e.getClass().getSimpleName());
            throw new IOException("Failed to apply redactions to PDF", e);
        }
    }

    private static boolean hasCoordinates(final Redaction r) {
        return r.getUpperRightX() > r.getLowerLeftX() && r.getUpperRightY() > r.getLowerLeftY();
    }
}
