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

import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.phileas.model.filtering.BinaryDocumentFilterResult;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.policy.Identifiers;
import ai.philterd.phileas.policy.filters.Ssn;
import ai.philterd.phileas.services.strategies.rules.SsnFilterStrategy;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import ai.philterd.phileas.services.filters.filtering.PdfFilterService;
import ai.philterd.arbiter.core.model.Redaction;
import ai.philterd.arbiter.core.model.RedactionResponse;
import ai.philterd.arbiter.philter.PhilterClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

@Service("phileasClient")
public class PhileasClient implements PhilterClient {

    @Override
    public RedactionResponse redact(String text, String context) throws IOException {
        // Create a redaction policy that includes SSNs
        Policy policy = new Policy();
        
        Identifiers identifiers = new Identifiers();
        Ssn ssn = new Ssn();
        
        SsnFilterStrategy ssnFilterStrategy = new SsnFilterStrategy();
        ssnFilterStrategy.setStrategy("REDACT");
        ssn.setSsnFilterStrategies(List.of(ssnFilterStrategy));
        
        identifiers.setSsn(ssn);
        policy.setIdentifiers(identifiers);
        
        // Use Phileas to redact the text
        try {
            Properties properties = new Properties();
            PhileasConfiguration phileasConfiguration = new PhileasConfiguration(properties);
            
            PlainTextFilterService filterService = new PlainTextFilterService(phileasConfiguration, null, null, null);
            TextFilterResult response = filterService.filter(policy, context, text);
            
            // Re-calculate redaction positions in the FINAL text
            List<Redaction> finalRedactions = new ArrayList<>();
            
            // It's easier to iterate forwards and keep track of offset
            StringBuilder sb = new StringBuilder(text);
            List<Span> spans = new ArrayList<>(response.getExplanation().appliedSpans());
            
            // Distinct spans
            List<Span> distinctSpans = new ArrayList<>();
            for (Span span : spans) {
                if (distinctSpans.stream().noneMatch(s -> s.getCharacterStart() == span.getCharacterStart() && s.getCharacterEnd() == span.getCharacterEnd())) {
                    distinctSpans.add(span);
                }
            }
            
            distinctSpans.sort((a, b) -> a.getCharacterStart() - b.getCharacterStart());
            int offset = 0;
            for (Span span : distinctSpans) {
                String id = UUID.randomUUID().toString();
                String replacement = span.getFilterType().getType().toUpperCase();
                
                int startInFinal = span.getCharacterStart() + offset;
                sb.replace(startInFinal, span.getCharacterEnd() + offset, replacement);
                
                Redaction r = new Redaction();
                r.setId(id);
                r.setText(span.getText());
                r.setStart(startInFinal);
                r.setEnd(startInFinal + replacement.length());
                r.setType(span.getFilterType().getType());
                finalRedactions.add(r);
                
                offset += (replacement.length() - (span.getCharacterEnd() - span.getCharacterStart()));
            }
            
            return new RedactionResponse(text, sb.toString(), finalRedactions);
        } catch (Exception e) {
            throw new IOException("Failed to redact text via Phileas", e);
        }
    }

    @Override
    public RedactionResponse redactPdf(byte[] pdfBytes, String context) throws IOException {
        Policy policy = new Policy();

        Identifiers identifiers = new Identifiers();
        Ssn ssn = new Ssn();

        SsnFilterStrategy ssnFilterStrategy = new SsnFilterStrategy();
        ssnFilterStrategy.setStrategy("REDACT");
        ssn.setSsnFilterStrategies(List.of(ssnFilterStrategy));

        identifiers.setSsn(ssn);
        policy.setIdentifiers(identifiers);

        try {
            Properties properties = new Properties();
            PhileasConfiguration phileasConfiguration = new PhileasConfiguration(properties);

            PdfFilterService filterService = new PdfFilterService(phileasConfiguration, null, null, null);
            BinaryDocumentFilterResult response = filterService.filter(policy, context, pdfBytes, MimeType.APPLICATION_PDF);

            List<Redaction> finalRedactions = new ArrayList<>();
            for (Span span : response.getExplanation().appliedSpans()) {
                Redaction r = new Redaction();
                r.setId(UUID.randomUUID().toString());
                r.setText(span.getText());
                r.setStart(span.getCharacterStart());
                r.setEnd(span.getCharacterEnd());
                r.setType(span.getFilterType().getType());
                r.setPageNumber(span.getPageNumber());
                r.setLowerLeftX(span.getLowerLeftX());
                r.setLowerLeftY(span.getLowerLeftY());
                r.setUpperRightX(span.getUpperRightX());
                r.setUpperRightY(span.getUpperRightY());
                finalRedactions.add(r);
            }

            // For PDF, the "redactedText" doesn't quite apply the same way as plain text,
            // but we can extract the text from the redacted PDF or just return the metadata.
            // Arbiter uses the text-based UI for review.
            // Let's extract text from the original PDF for the UI.
            org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes);
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            String originalText = stripper.getText(document);
            document.close();

            // Distinct spans by position to avoid double redaction in PDF metadata list
            List<Redaction> distinctFinalRedactions = new ArrayList<>();
            for (Redaction r : finalRedactions) {
                if (distinctFinalRedactions.stream().noneMatch(existing -> 
                    existing.getPageNumber() == r.getPageNumber() && 
                    existing.getLowerLeftX() == r.getLowerLeftX() && 
                    existing.getLowerLeftY() == r.getLowerLeftY())) {
                    distinctFinalRedactions.add(r);
                }
            }

            // We need to map the spans to the extracted text for the UI.
            // Phileas PdfFilterService doesn't directly give us the redacted plain text string.
            // For now, let's use the plain text redaction logic to get the UI view, 
            // but return the PDF-specific metadata.
            RedactionResponse textResponse = redact(originalText, context);

            return new RedactionResponse(originalText, textResponse.getRedactedText(), distinctFinalRedactions);

        } catch (Exception e) {
            throw new IOException("Failed to redact PDF via Phileas", e);
        }
    }
}
