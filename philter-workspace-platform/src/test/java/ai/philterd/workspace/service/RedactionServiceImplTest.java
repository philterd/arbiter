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
import ai.philterd.workspace.philter.PhilterClient;
import ai.philterd.workspace.philter.PhilterClientFactory;
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

    private final PhilterClientFactory factory = mock(PhilterClientFactory.class);
    private final PhilterSettings philter = new PhilterSettings("http://philter:8080", "sk_test", PhilterTlsConfig.none());
    private final RedactionServiceImpl service = new RedactionServiceImpl(factory, philter);

    @Test
    void redactsThroughTheConfiguredPhilterWithTheBatchPolicy() throws Exception {
        final PhilterClient client = mock(PhilterClient.class);
        when(factory.create("http://philter:8080", "sk_test", "claims")).thenReturn(client);
        when(client.redact("text", "")).thenReturn(new RedactionResponse("text", "redacted", new ArrayList<>()));

        assertEquals("redacted", service.redactText("text", "claims", null).getRedactedText());
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
