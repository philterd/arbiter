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
import ai.philterd.workspace.philter.PhilterRedactions;
import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.policy.graphical.BoundingBox;
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

    private final PhilterSettings philter;

    public RedactionServiceImpl(final PhilterSettings philter) {
        this.philter = philter;
    }

    /** Through the SDK client for Philter Workspace's own key, the one every background call to Philter uses. */
    @Override
    public RedactionResponse redactText(final String text, final String policyName,
                                        final String context) throws IOException {
        return PhilterRedactions.redact(philter.client(), text, policyName, context);
    }

    @Override
    public RedactionResponse redactPdf(final InputStream inputStream, final String policyName,
                                       final String context) throws IOException {
        // TODO: Philter's PDF endpoints return a redacted PDF but no spans, so there is nothing to review (#58).
        throw new IOException("PDF review through Philter is not yet available.");
    }

    /**
     * Applies the reviewed spans to the PDF with the Phileas library: each span becomes a filled box at its
     * page and position, and Phileas rasterizes the pages, so the text under a box is gone from the output.
     * Refuses, rather than return the original, when a span has no position on the page to draw its box at.
     *
     * <p>The boxes go in as the policy's bounding boxes. Phileas matches the spans it is given to text by a
     * line hash from its own detection, which reviewed spans do not carry, so a span passed as a span would
     * cover nothing.
     */
    @Override
    public byte[] getRedactedPdf(final InputStream originalPdf,
                                 final RedactionResponse redactionResponse) throws IOException {
        final byte[] pdfBytes = originalPdf.readAllBytes();
        final List<Redaction> redactions = redactionResponse == null || redactionResponse.getRedactions() == null
                ? List.of() : redactionResponse.getRedactions();

        final List<BoundingBox> boxes = new ArrayList<>();
        for (Redaction r : redactions) {
            if (!hasCoordinates(r) || r.getPageNumber() < 0) {
                throw new IOException("A redaction has no position in the PDF, so a redacted PDF cannot be "
                        + "produced. PDF redaction through Philter is not yet available.");
            }
            boxes.add(box(r));
        }

        try {
            final Policy policy = new Policy();
            policy.getGraphical().setBoundingBoxes(boxes);
            final PdfFilterService filterService = new PdfFilterService(
                    new PhileasConfiguration(new Properties()), null, null, null);
            return filterService.apply(policy, pdfBytes, List.of(), MimeType.APPLICATION_PDF);
        } catch (Exception e) {
            log.error("Failed to apply redactions to PDF via Phileas: {}", e.getClass().getSimpleName());
            throw new IOException("Failed to apply redactions to PDF", e);
        }
    }

    /**
     * A filled box over a span. A span's page counts from 0, as Phileas's detection reports it, and a
     * bounding box's from 1. It is padded as Phileas pads the boxes it draws for the spans it detects, so it
     * covers the glyphs' descenders and ascenders.
     */
    static BoundingBox box(final Redaction r) {
        final BoundingBox box = new BoundingBox();
        box.setPage(r.getPageNumber() + 1);
        box.setX(r.getLowerLeftX());
        box.setY(r.getLowerLeftY() - 3);
        box.setW(r.getUpperRightX() - r.getLowerLeftX());
        box.setH(r.getUpperRightY() - r.getLowerLeftY() + 10);
        return box;
    }

    private static boolean hasCoordinates(final Redaction r) {
        return r.getUpperRightX() > r.getLowerLeftX() && r.getUpperRightY() > r.getLowerLeftY();
    }
}
