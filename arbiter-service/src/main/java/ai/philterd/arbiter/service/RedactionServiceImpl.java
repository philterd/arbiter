/*
 * Copyright 2026 Philterd
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
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.core.model.Redaction;
import ai.philterd.arbiter.core.model.RedactionResponse;
import ai.philterd.arbiter.philter.PhilterClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

@Service
public class RedactionServiceImpl implements RedactionService {

    private static final Logger log = LoggerFactory.getLogger(RedactionServiceImpl.class);

    private final PhilterClient philterClient;
    private final PhilterClient phileasClient;

    @Value("${philter.url:}")
    private String philterUrl;

    public RedactionServiceImpl(@Qualifier("philterClient") PhilterClient philterClient,
                                @Qualifier("phileasClient") PhilterClient phileasClient) {
        this.philterClient = philterClient;
        this.phileasClient = phileasClient;
    }

    private PhilterClient getClient() {
        if (philterUrl != null && !philterUrl.isBlank()) {
            log.info("Using Philter remote instance at {}", philterUrl);
            return philterClient;
        } else {
            log.info("Using local Phileas library");
            return phileasClient;
        }
    }

    @Override
    public RedactionResponse redactText(String text) throws IOException {
        String context = UUID.randomUUID().toString();
        return getClient().redact(text, context);
    }

    @Override
    public RedactionResponse redactPdf(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        String context = UUID.randomUUID().toString();
        return getClient().redactPdf(bytes, context);
    }

    @Override
    public byte[] getRedactedPdf(InputStream originalPdf, RedactionResponse redactionResponse) throws IOException {
        // Apply redactions back to the PDF.
        byte[] pdfBytes = originalPdf.readAllBytes();
        
        if (getClient() == phileasClient) {
            try {
                Properties properties = new Properties();
                ai.philterd.phileas.PhileasConfiguration phileasConfiguration = new ai.philterd.phileas.PhileasConfiguration(properties);
                ai.philterd.phileas.services.filters.filtering.PdfFilterService filterService = new ai.philterd.phileas.services.filters.filtering.PdfFilterService(phileasConfiguration, null, null, null);
                
                // Convert our Redaction objects back to Phileas Spans
                java.util.List<ai.philterd.phileas.model.filtering.Span> spans = new java.util.ArrayList<>();
                for (Redaction r : redactionResponse.getRedactions()) {
                    // Only add spans that have coordinates
                    if (r.getPageNumber() >= 0) {
                        ai.philterd.phileas.model.filtering.Span span = new ai.philterd.phileas.model.filtering.Span();
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
                }
                
                // Phileas apply() needs a non-null Policy if it uses it for anything, but PdfFilterService.apply usually doesn't.
                // However, let's provide a basic one just in case.
                ai.philterd.phileas.policy.Policy policy = new ai.philterd.phileas.policy.Policy();
                return filterService.apply(policy, pdfBytes, spans, ai.philterd.phileas.model.filtering.MimeType.APPLICATION_PDF);
            } catch (Exception e) {
                log.error("Failed to apply redactions to PDF via Phileas", e);
                throw new IOException("Failed to apply redactions to PDF", e);
            }
        } else {
            // For Philter remote, we'd ideally call a Philter apply API.
            // For now, return original as placeholder.
            log.warn("PDF redaction back-apply not implemented for Philter remote - returning original.");
            return pdfBytes;
        }
    }
}
