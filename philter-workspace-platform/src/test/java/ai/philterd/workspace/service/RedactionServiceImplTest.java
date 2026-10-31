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

import ai.philterd.workspace.core.model.Redaction;
import ai.philterd.workspace.core.model.RedactionResponse;
import ai.philterd.workspace.philter.PhilterTlsConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedactionServiceImplTest {

    private final PhilterSettings philter = mock(PhilterSettings.class);
    private final RedactionServiceImpl service = new RedactionServiceImpl(philter);

    @Test
    void redactsWithTheSdkClientPhilterWorkspaceHoldsAndTheBatchPolicy() throws Exception {
        final ai.philterd.philter.PhilterClient client = mock(ai.philterd.philter.PhilterClient.class);
        when(philter.client()).thenReturn(client);
        final ai.philterd.philter.model.Span span = new ai.philterd.philter.model.Span();
        span.setCharacterStart(4);
        span.setCharacterEnd(15);
        span.setFilterType("SSN");
        span.setText("123-45-6789");
        final ai.philterd.philter.model.Explanation explanation = new ai.philterd.philter.model.Explanation();
        explanation.setAppliedSpans(List.of(span));
        final ai.philterd.philter.model.ExplainResponse explained = new ai.philterd.philter.model.ExplainResponse();
        explained.setExplanation(explanation);
        // No context is sent rather than an empty one.
        when(client.explain(null, "claims", "SSN 123-45-6789 here")).thenReturn(explained);

        final RedactionResponse response = service.redactText("SSN 123-45-6789 here", "claims", null);
        assertEquals("SSN SSN here", response.getRedactedText());
        assertEquals("ssn", response.getRedactions().get(0).getType());
    }

    @Test
    void aPdfIsNotSentToPhilterUntilItCanBeReviewed() {
        final java.io.IOException e = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> service.redactPdf(new java.io.ByteArrayInputStream(new byte[]{1}), "claims", "ctx"));
        assertEquals("PDF review through Philter is not yet available.", e.getMessage());
        org.mockito.Mockito.verifyNoInteractions(philter);
    }

    @Test
    void refusesARedactedPdfWhenASpanHasNoPositionRatherThanReturnTheOriginal() throws Exception {
        final byte[] original = pdf();
        final Redaction unlocated = new Redaction();
        unlocated.setText("value");
        unlocated.setType("ssn");
        final RedactionResponse response = new RedactionResponse("", "", List.of(unlocated));

        assertThrows(IOException.class,
                () -> service.getRedactedPdf(new ByteArrayInputStream(original), response));
    }

    /** A span over "Invented" on the line at y=700, on {@code page}, counting from 0. */
    private static Redaction located(final int page) {
        final Redaction r = new Redaction();
        r.setText("Invented");
        r.setType("ssn");
        r.setPageNumber(page);
        r.setLowerLeftX(50);
        r.setLowerLeftY(698);
        r.setUpperRightX(100);
        r.setUpperRightY(707);
        return r;
    }

    @Test
    void aReviewedSpanIsCoveredOnItsPageAndNothingElseIs() throws Exception {
        final byte[] original = pdf(2);
        final byte[] redacted = service.getRedactedPdf(new ByteArrayInputStream(original),
                new RedactionResponse("", "", List.of(located(1))));

        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(redacted)) {
            assertEquals(2, document.getNumberOfPages());
            assertEquals(612f, document.getPage(1).getMediaBox().getWidth());
            assertEquals(792f, document.getPage(1).getMediaBox().getHeight());
            // Pages are rasterized, so no text can be lifted from under a box.
            assertEquals("", new org.apache.pdfbox.text.PDFTextStripper().getText(document).trim());
            final org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            final java.awt.image.BufferedImage second = renderer.renderImageWithDPI(1, 72);
            final java.awt.image.BufferedImage first = renderer.renderImageWithDPI(0, 72);
            // 72 dpi: x as in PDF space, y from the top (792 - y). The box covers (50..100, 695..717).
            assertEquals(0x000000, second.getRGB(75, 90) & 0xFFFFFF, "black over the span on its page");
            assertEquals(0xFFFFFF, second.getRGB(300, 400) & 0xFFFFFF, "white away from it");
            assertTrue((first.getRGB(75, 90) & 0xFFFFFF) != 0x000000, "the first page is not covered there");
        }
    }

    @Test
    void aSpanOnAPageThePdfDoesNotHaveIsRefused() throws Exception {
        final byte[] original = pdf(1);
        assertThrows(IOException.class, () -> service.getRedactedPdf(new ByteArrayInputStream(original),
                new RedactionResponse("", "", List.of(located(1)))));
        final Redaction negative = located(0);
        negative.setPageNumber(-1);
        assertThrows(IOException.class, () -> service.getRedactedPdf(new ByteArrayInputStream(original),
                new RedactionResponse("", "", List.of(negative))));
    }

    @Test
    void aBoxIsPaddedAsPhileasPadsItsOwn() {
        final ai.philterd.phileas.policy.graphical.BoundingBox box = RedactionServiceImpl.box(located(0));
        assertEquals(1, box.getPage());
        assertEquals(50f, box.getX());
        assertEquals(695f, box.getY());
        assertEquals(50f, box.getW());
        assertEquals(19f, box.getH());
    }

    private static byte[] pdf() throws IOException {
        return pdf(1);
    }

    /** {@code pages} letter-size pages, each with an invented line of text at y=700. */
    private static byte[] pdf(final int pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                final PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText("Invented value 000-00-0000");
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
