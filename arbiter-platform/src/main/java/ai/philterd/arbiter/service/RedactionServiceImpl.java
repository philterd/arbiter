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

import ai.philterd.arbiter.core.model.Redaction;
import ai.philterd.arbiter.core.model.RedactionResponse;
import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.philter.PhilterClient;
import ai.philterd.arbiter.philter.PhilterClientFactory;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

@Service
public class RedactionServiceImpl implements RedactionService {

    private static final Logger log = LoggerFactory.getLogger(RedactionServiceImpl.class);

    private final PhilterClient phileasClient;
    private final PhilterClientFactory philterClientFactory;
    private final PhilterInstanceRepository philterInstanceRepository;
    private final ai.philterd.arbiter.service.SymmetricCipher symmetricCipher;
    private final DataSourceHostAllowList hostAllowList;

    public RedactionServiceImpl(@Qualifier("phileasClient") final PhilterClient phileasClient,
                                final PhilterClientFactory philterClientFactory,
                                final PhilterInstanceRepository philterInstanceRepository,
                                final ai.philterd.arbiter.service.SymmetricCipher symmetricCipher,
                                final DataSourceHostAllowList hostAllowList) {
        this.phileasClient = phileasClient;
        this.philterClientFactory = philterClientFactory;
        this.philterInstanceRepository = philterInstanceRepository;
        this.symmetricCipher = symmetricCipher;
        this.hostAllowList = hostAllowList;
    }

    private PhilterClient getClient(final String philterInstanceId) throws IOException {
        if (philterInstanceId != null && !philterInstanceId.isBlank()) {
            final Optional<PhilterInstance> instance = philterInstanceRepository.findById(philterInstanceId);
            if (instance.isPresent()) {
                final PhilterInstance pi = instance.get();
                // Re-validate at call time: a stored Philter endpoint that was on the
                // allow-list when it was saved may no longer be (configuration changed,
                // private-range default-deny tightened, etc.). Refuse rather than make
                // an outbound call to a now-disallowed host.
                final String url = requireAllowedBaseUrl(pi);
                String apiKey = null;
                try {
                    apiKey = symmetricCipher.decrypt(pi.getEncryptedApiKey());
                } catch (Exception e) {
                    log.warn("Could not decrypt API key for Philter instance \"{}\": {}",
                            pi.getName(), e.getMessage());
                }
                log.info("Using Philter remote instance \"{}\" at {} (api key {})",
                        pi.getName(), url, (apiKey != null && !apiKey.isBlank() ? "set" : "not set"));
                return philterClientFactory.create(url, apiKey);
            }
            log.warn("Philter instance {} not found; falling back to local Phileas.", philterInstanceId);
        }
        log.info("Using local Phileas library");
        return phileasClient;
    }

    private static String baseUrl(final PhilterInstance instance) {
        String host = instance.getEndpoint();
        if (host == null || host.isBlank()) host = "localhost";
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        return host + ":" + instance.getPort();
    }

    private String requireAllowedBaseUrl(final PhilterInstance instance) throws IOException {
        final String url = baseUrl(instance);
        if (!hostAllowList.isAllowed(url)) {
            throw new IOException("Philter instance \"" + instance.getName()
                    + "\" host is not on the data-source allow-list "
                    + "(arbiter.data-sources.allowed-hosts).");
        }
        return url;
    }

    @Override
    public RedactionResponse redactText(final String text, final String philterInstanceId, final String context) throws IOException {
        return getClient(philterInstanceId).redact(text, context == null ? "" : context);
    }

    @Override
    public RedactionResponse redactPdf(final InputStream inputStream, final String philterInstanceId, final String context) throws IOException {
        final byte[] bytes = inputStream.readAllBytes();
        return getClient(philterInstanceId).redactPdf(bytes, context == null ? "" : context);
    }

    @Override
    public byte[] getRedactedPdf(final InputStream originalPdf, final RedactionResponse redactionResponse,
                                 final String philterInstanceId) throws IOException {
        // Apply redactions back to the PDF.
        final byte[] pdfBytes = originalPdf.readAllBytes();

        final PhilterClient client = getClient(philterInstanceId);
        if (client == phileasClient) {
            try {
                final Properties properties = new Properties();
                final ai.philterd.phileas.PhileasConfiguration phileasConfiguration = new ai.philterd.phileas.PhileasConfiguration(properties);
                final ai.philterd.phileas.services.filters.filtering.PdfFilterService filterService = new ai.philterd.phileas.services.filters.filtering.PdfFilterService(phileasConfiguration, null, null, null);

                // Convert our Redaction objects back to Phileas Spans
                final java.util.List<ai.philterd.phileas.model.filtering.Span> spans = new java.util.ArrayList<>();
                for (Redaction r : redactionResponse.getRedactions()) {
                    // Only add spans that have coordinates
                    if (r.getPageNumber() >= 0) {
                        final ai.philterd.phileas.model.filtering.Span span = new ai.philterd.phileas.model.filtering.Span();
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
                final ai.philterd.phileas.policy.Policy policy = new ai.philterd.phileas.policy.Policy();
                return filterService.apply(policy, pdfBytes, spans, ai.philterd.phileas.model.filtering.MimeType.APPLICATION_PDF);
            } catch (Exception e) {
                log.error("Failed to apply redactions to PDF via Phileas", e);
                throw new IOException("Failed to apply redactions to PDF", e);
            }
        } else {
            // TODO: Implement PDF redaction back-apply for remote Philter. Returning the
            // original bytes is a confidentiality breach — the user clicks "Download
            // redacted" and gets the unredacted file. Until a Philter "apply spans → PDF"
            // call is wired up here, the caller should refuse the download for batches
            // bound to a remote Philter instance instead of falling through to this branch.
            log.warn("PDF redaction back-apply not implemented for Philter remote - returning original.");
            return pdfBytes;
        }
    }
}
