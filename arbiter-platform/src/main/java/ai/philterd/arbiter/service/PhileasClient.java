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
package ai.philterd.arbiter.service;

import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.phileas.model.filtering.BinaryDocumentFilterResult;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.policy.Identifiers;
import ai.philterd.phileas.policy.filters.Age;
import ai.philterd.phileas.policy.filters.CreditCard;
import ai.philterd.phileas.policy.filters.Date;
import ai.philterd.phileas.policy.filters.EmailAddress;
import ai.philterd.phileas.policy.filters.IpAddress;
import ai.philterd.phileas.policy.filters.PhoneNumber;
import ai.philterd.phileas.policy.filters.Ssn;
import ai.philterd.phileas.services.strategies.rules.AgeFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.CreditCardFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.DateFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.EmailAddressFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.IpAddressFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.PhoneNumberFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.SsnFilterStrategy;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import ai.philterd.phileas.services.filters.filtering.PdfFilterService;
import ai.philterd.arbiter.core.model.Redaction;
import ai.philterd.arbiter.core.model.RedactionResponse;
import ai.philterd.arbiter.philter.PhilterClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Service("phileasClient")
public class PhileasClient implements PhilterClient {

    @Override
    public RedactionResponse redact(final String text, final String context) throws IOException {
        // Create a redaction policy that includes SSNs
        final Policy policy = new Policy();

        final Identifiers identifiers = new Identifiers();

        final Age age = new Age();
        final AgeFilterStrategy ageFilterStrategy = new AgeFilterStrategy();
        ageFilterStrategy.setStrategy("REDACT");
        age.setAgeFilterStrategies(List.of(ageFilterStrategy));
        identifiers.setAge(age);

        final CreditCard creditCard = new CreditCard();
        final CreditCardFilterStrategy creditCardFilterStrategy = new CreditCardFilterStrategy();
        creditCardFilterStrategy.setStrategy("REDACT");
        creditCard.setCreditCardFilterStrategies(List.of(creditCardFilterStrategy));
        identifiers.setCreditCard(creditCard);

        final Date date = new Date();
        final DateFilterStrategy dateFilterStrategy = new DateFilterStrategy();
        dateFilterStrategy.setStrategy("REDACT");
        date.setDateFilterStrategies(List.of(dateFilterStrategy));
        identifiers.setDate(date);

        final EmailAddress emailAddress = new EmailAddress();
        final EmailAddressFilterStrategy emailAddressFilterStrategy = new EmailAddressFilterStrategy();
        emailAddressFilterStrategy.setStrategy("REDACT");
        emailAddress.setEmailAddressFilterStrategies(List.of(emailAddressFilterStrategy));
        identifiers.setEmailAddress(emailAddress);

        final IpAddress ipAddress = new IpAddress();
        final IpAddressFilterStrategy ipAddressFilterStrategy = new IpAddressFilterStrategy();
        ipAddressFilterStrategy.setStrategy("REDACT");
        ipAddress.setIpAddressFilterStrategies(List.of(ipAddressFilterStrategy));
        identifiers.setIpAddress(ipAddress);

        final PhoneNumber phoneNumber = new PhoneNumber();
        final PhoneNumberFilterStrategy phoneNumberFilterStrategy = new PhoneNumberFilterStrategy();
        phoneNumberFilterStrategy.setStrategy("REDACT");
        phoneNumber.setPhoneNumberFilterStrategies(List.of(phoneNumberFilterStrategy));
        identifiers.setPhoneNumber(phoneNumber);

        final Ssn ssn = new Ssn();
        final SsnFilterStrategy ssnFilterStrategy = new SsnFilterStrategy();
        ssnFilterStrategy.setStrategy("REDACT");
        ssn.setSsnFilterStrategies(List.of(ssnFilterStrategy));
        identifiers.setSsn(ssn);

        policy.setIdentifiers(identifiers);
        
        // Use Phileas to redact the text
        try {
            final Properties properties = new Properties();
            final PhileasConfiguration phileasConfiguration = new PhileasConfiguration(properties);

            final PlainTextFilterService filterService = new PlainTextFilterService(phileasConfiguration, null, null, null);
            final TextFilterResult response = filterService.filter(policy, context, text);

            // Re-calculate redaction positions in the FINAL text
            final List<Redaction> finalRedactions = new ArrayList<>();

            // It's easier to iterate forwards and keep track of offset
            final StringBuilder sb = new StringBuilder(text);
            final List<Span> spans = new ArrayList<>(response.getExplanation().appliedSpans());

            // Distinct spans
            final List<Span> distinctSpans = new ArrayList<>();
            for (Span span : spans) {
                if (distinctSpans.stream().noneMatch(s -> s.getCharacterStart() == span.getCharacterStart() && s.getCharacterEnd() == span.getCharacterEnd())) {
                    distinctSpans.add(span);
                }
            }
            
            distinctSpans.sort(Comparator.comparingInt(Span::getCharacterStart));
            int offset = 0;
            for (Span span : distinctSpans) {
                final String id = UUID.randomUUID().toString();
                final String replacement = span.getFilterType().getType().toUpperCase();

                final int startInFinal = span.getCharacterStart() + offset;
                sb.replace(startInFinal, span.getCharacterEnd() + offset, replacement);

                final Redaction r = new Redaction();
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
    public RedactionResponse redactPdf(final byte[] pdfBytes, final String context) throws IOException {
        final Policy policy = new Policy();

        final Identifiers identifiers = new Identifiers();

        final Age age = new Age();
        final AgeFilterStrategy ageFilterStrategy = new AgeFilterStrategy();
        ageFilterStrategy.setStrategy("REDACT");
        age.setAgeFilterStrategies(List.of(ageFilterStrategy));
        identifiers.setAge(age);

        final CreditCard creditCard = new CreditCard();
        final CreditCardFilterStrategy creditCardFilterStrategy = new CreditCardFilterStrategy();
        creditCardFilterStrategy.setStrategy("REDACT");
        creditCard.setCreditCardFilterStrategies(List.of(creditCardFilterStrategy));
        identifiers.setCreditCard(creditCard);

        final Date date = new Date();
        final DateFilterStrategy dateFilterStrategy = new DateFilterStrategy();
        dateFilterStrategy.setStrategy("REDACT");
        date.setDateFilterStrategies(List.of(dateFilterStrategy));
        identifiers.setDate(date);

        final EmailAddress emailAddress = new EmailAddress();
        final EmailAddressFilterStrategy emailAddressFilterStrategy = new EmailAddressFilterStrategy();
        emailAddressFilterStrategy.setStrategy("REDACT");
        emailAddress.setEmailAddressFilterStrategies(List.of(emailAddressFilterStrategy));
        identifiers.setEmailAddress(emailAddress);

        final IpAddress ipAddress = new IpAddress();
        final IpAddressFilterStrategy ipAddressFilterStrategy = new IpAddressFilterStrategy();
        ipAddressFilterStrategy.setStrategy("REDACT");
        ipAddress.setIpAddressFilterStrategies(List.of(ipAddressFilterStrategy));
        identifiers.setIpAddress(ipAddress);

        final PhoneNumber phoneNumber = new PhoneNumber();
        final PhoneNumberFilterStrategy phoneNumberFilterStrategy = new PhoneNumberFilterStrategy();
        phoneNumberFilterStrategy.setStrategy("REDACT");
        phoneNumber.setPhoneNumberFilterStrategies(List.of(phoneNumberFilterStrategy));
        identifiers.setPhoneNumber(phoneNumber);

        final Ssn ssn = new Ssn();
        final SsnFilterStrategy ssnFilterStrategy = new SsnFilterStrategy();
        ssnFilterStrategy.setStrategy("REDACT");
        ssn.setSsnFilterStrategies(List.of(ssnFilterStrategy));
        identifiers.setSsn(ssn);

        policy.setIdentifiers(identifiers);

        try {
            final Properties properties = new Properties();
            final PhileasConfiguration phileasConfiguration = new PhileasConfiguration(properties);

            final PdfFilterService filterService = new PdfFilterService(phileasConfiguration, null, null, null);
            final BinaryDocumentFilterResult response = filterService.filter(policy, context, pdfBytes, MimeType.APPLICATION_PDF);

            final List<Redaction> finalRedactions = new ArrayList<>();
            for (Span span : response.getExplanation().appliedSpans()) {
                final Redaction r = new Redaction();
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
            final org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes);
            final org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            final String originalText = stripper.getText(document);
            document.close();

            // Distinct spans by position to avoid double redaction in PDF metadata list
            final List<Redaction> distinctFinalRedactions = new ArrayList<>();
            for (Redaction r : finalRedactions) {
                if (distinctFinalRedactions.stream().noneMatch(existing -> 
                    existing.getPageNumber() == r.getPageNumber() && 
                    existing.getLowerLeftX() == r.getLowerLeftX() && 
                    existing.getLowerLeftY() == r.getLowerLeftY())) {
                    distinctFinalRedactions.add(r);
                }
            }

            // TODO: Surface the redacted plain text directly from PdfFilterService rather
            // than re-running the text redactor over the extracted text. The current path
            // can drift if the PDF and text redactors disagree on span boundaries (e.g.
            // when a token spans a line break in the PDF), so the UI preview may differ
            // from the bytes the user actually downloads.
            final RedactionResponse textResponse = redact(originalText, context);

            return new RedactionResponse(originalText, textResponse.getRedactedText(), distinctFinalRedactions);

        } catch (Exception e) {
            throw new IOException("Failed to redact PDF via Phileas", e);
        }
    }
    @Override
    public Map<String, Object> explain(final String text, final String context) throws IOException {
        // Mocking explain for Phileas local
        final RedactionResponse response = redact(text, context);
        final List<Map<String, Object>> explanation = response.getRedactions().stream().map(r -> {
            final Map<String, Object> map = new java.util.HashMap<>();
            map.put("text", r.getText());
            map.put("type", r.getType());
            map.put("characterStart", r.getStart());
            map.put("characterEnd", r.getEnd());
            map.put("confidence", 1.0);
            return map;
        }).collect(java.util.stream.Collectors.toList());

        final Map<String, Object> result = new java.util.HashMap<>();
        result.put("filteredText", response.getRedactedText());
        result.put("explanation", explanation);
        return result;
    }

    @Override
    public String redact(final String text, final String context, final List<ai.philterd.arbiter.core.model.Redaction> approvedSpans) throws IOException {
        // Mocking manual redaction for Phileas local
        final StringBuilder sb = new StringBuilder(text);
        final List<ai.philterd.arbiter.core.model.Redaction> sortedSpans = new ArrayList<>(approvedSpans);
        sortedSpans.sort((a, b) -> b.getStart() - a.getStart());

        for (ai.philterd.arbiter.core.model.Redaction span : sortedSpans) {
            sb.replace(span.getStart(), span.getEnd(), span.getType().toUpperCase());
        }

        return sb.toString();
    }

}
