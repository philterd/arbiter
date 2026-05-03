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
package ai.philterd.arbiter.philter;

import ai.philterd.arbiter.core.model.Redaction;
import ai.philterd.arbiter.core.model.RedactionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service("philterClient")
public class PhilterClientImpl implements PhilterClient {

    private static final Logger log = LoggerFactory.getLogger(PhilterClientImpl.class);

    private final RestTemplate restTemplate;
    private final String philterUrl;

    public PhilterClientImpl(RestTemplate restTemplate, @Value("${philter.url:http://localhost:8080}") String philterUrl) {
        this.restTemplate = restTemplate;
        this.philterUrl = philterUrl;
    }

    @Override
    public RedactionResponse redactPdf(byte[] pdfBytes, String context) throws IOException {
        // Basic implementation for Philter remote
        // In a real app, we would call the Philter PDF redaction endpoint.
        // For now, we'll convert to text and redact.
        String text = new String(pdfBytes);
        return redact(text, context);
    }

    @Override
    public Map<String, Object> explain(String text, String context) throws IOException {
        String explainUrl = philterUrl + "/api/explain?context=" + context;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> explainRequest = new HttpEntity<>(text, headers);

        try {
            return restTemplate.postForObject(explainUrl, explainRequest, Map.class);
        } catch (Exception e) {
            log.error("Error calling Philter explain API", e);
            throw new IOException("Failed to explain text via Philter", e);
        }
    }

    @Override
    public String redact(String text, String context, List<ai.philterd.arbiter.core.model.Redaction> approvedSpans) throws IOException {
        // In a real Philter API, we might send the spans to be redacted.
        // Philter's /api/filter accepts an optional list of spans to redact OR we just let Philter do its thing.
        // But for HITL, we only want to redact the APPROVED spans.
        // Philter has an endpoint where you can provide the spans.
        
        // For this implementation, let's assume we send a POST to /api/filter with the spans in a specific format if supported,
        // or we manually redact them if the API doesn't support passing specific spans.
        
        // Let's assume Philter has a /api/redact endpoint that takes text and a list of spans.
        String redactUrl = philterUrl + "/api/redact?context=" + context;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        Map<String, Object> body = Map.of(
            "text", text,
            "spans", approvedSpans
        );
        
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        
        try {
            return restTemplate.postForObject(redactUrl, request, String.class);
        } catch (Exception e) {
            log.error("Error calling Philter redact API", e);
            throw new IOException("Failed to redact text via Philter", e);
        }
    }

    @Override
    public RedactionResponse redact(String text, String context) throws IOException {
        String url = philterUrl + "/api/filter?context=" + context;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        HttpEntity<String> request = new HttpEntity<>(text, headers);

        // Philter returns the redacted text in the body and redactions in the x-philter-explanation header if requested,
        // but by default, we can also use the explain API or just handle the basic redaction.
        // For Arbiter, we need both the redacted text AND the list of redactions to show them in the UI.
        
        // Actually, Philter's /api/filter returns redacted text.
        // To get redactions (explanation), we might need to call /api/explain or use headers.
        // Let's assume we use /api/filter and it might provide explanations via a header or we use a separate call.
        // Standard Philter API: POST /api/filter returns redacted text.
        // If we want the spans, we should use the 'explain' feature.
        
        // Let's use /api/filter with Query Parameter 'explain=true' if supported, 
        // or call /api/explain which returns JSON with both.
        
        String explainUrl = philterUrl + "/api/explain?context=" + context;
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        
        HttpEntity<String> explainRequest = new HttpEntity<>(text, headers);
        
        try {
            Map<String, Object> response = restTemplate.postForObject(explainUrl, explainRequest, Map.class);
            
            String redactedText = (String) response.get("filteredText");
            List<Map<String, Object>> explanations = (List<Map<String, Object>>) response.get("explanation");
            
            List<Redaction> redactions = new ArrayList<>();
            StringBuilder sb = new StringBuilder(text);
            int offset = 0;

            if (explanations != null) {
                // Sort explanations by start index to process sequentially
                List<Map<String, Object>> sortedExplanations = new ArrayList<>(explanations);
                sortedExplanations.sort((a, b) -> (Integer) a.get("characterStart") - (Integer) b.get("characterStart"));

                for (Map<String, Object> exp : sortedExplanations) {
                    String type = (String) exp.get("type");
                    String replacement = type.toUpperCase();
                    int start = (Integer) exp.get("characterStart");
                    int end = (Integer) exp.get("characterEnd");

                    int startInFinal = start + offset;
                    sb.replace(startInFinal, end + offset, replacement);

                    Redaction r = new Redaction();
                    r.setId(UUID.randomUUID().toString());
                    r.setText((String) exp.get("text"));
                    r.setStart(startInFinal);
                    r.setEnd(startInFinal + replacement.length());
                    r.setType(type);
                    r.setConfidence((Double) exp.getOrDefault("confidence", 0.0));
                    redactions.add(r);

                    offset += (replacement.length() - (end - start));
                }
            }
            
            return new RedactionResponse(text, sb.toString(), redactions);
            
        } catch (Exception e) {
            log.error("Error calling Philter API", e);
            throw new IOException("Failed to redact text via Philter", e);
        }
    }
}
