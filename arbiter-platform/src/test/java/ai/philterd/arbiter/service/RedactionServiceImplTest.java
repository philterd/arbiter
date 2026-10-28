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

import ai.philterd.arbiter.core.model.Redaction;
import ai.philterd.arbiter.core.model.RedactionResponse;
import ai.philterd.arbiter.philter.PhilterClient;
import ai.philterd.arbiter.philter.PhilterClientFactory;
import ai.philterd.arbiter.philter.PhilterTlsConfig;
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

    @Test
    void appliesLocatedSpansToThePdf() throws Exception {
        final byte[] original = pdf();
        final Redaction located = new Redaction();
        located.setText("value");
        located.setType("ssn");
        located.setPageNumber(0);
        located.setLowerLeftX(50);
        located.setLowerLeftY(690);
        located.setUpperRightX(150);
        located.setUpperRightY(710);
        final RedactionResponse response = new RedactionResponse("", "", List.of(located));

        final byte[] redacted = service.getRedactedPdf(new ByteArrayInputStream(original), response);

        assertTrue(redacted.length > 0);
        assertFalse(java.util.Arrays.equals(original, redacted), "the original must never come back as redacted");
        assertEquals("%PDF", new String(redacted, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static byte[] pdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Invented value 000-00-0000");
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
