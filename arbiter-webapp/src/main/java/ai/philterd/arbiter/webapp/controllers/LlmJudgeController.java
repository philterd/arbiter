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
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.LlmJudgeDefaults;
import ai.philterd.arbiter.model.OllamaInstance;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.OllamaInstanceRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.LlmJudgeDefaultsService;
import ai.philterd.arbiter.service.UserGroupsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/v1")
public class LlmJudgeController {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeController.class);

    private static final String EXPLAIN_PROMPT = "Explain the risk of the PII in this document.";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MODELS_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration GENERATE_TIMEOUT = Duration.ofMinutes(3);

    private final OllamaInstanceRepository ollamaInstanceRepository;
    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final BatchRepository batchRepository;
    private final UserGroupsService userGroupsService;
    private final AuditLogService auditLogService;
    private final LlmJudgeDefaultsService llmJudgeDefaultsService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public LlmJudgeController(final OllamaInstanceRepository ollamaInstanceRepository,
                              final DocumentRepository documentRepository,
                              final SpanRepository spanRepository,
                              final BatchRepository batchRepository,
                              final UserGroupsService userGroupsService,
                              final AuditLogService auditLogService,
                              final LlmJudgeDefaultsService llmJudgeDefaultsService,
                              final ObjectMapper objectMapper) {
        this.ollamaInstanceRepository = ollamaInstanceRepository;
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.batchRepository = batchRepository;
        this.userGroupsService = userGroupsService;
        this.auditLogService = auditLogService;
        this.llmJudgeDefaultsService = llmJudgeDefaultsService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/ollama/{instanceId}/models")
    public Map<String, Object> listModels(@PathVariable final String instanceId) {
        final OllamaInstance instance = ollamaInstanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ollama instance not found."));
        try {
            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl(instance) + "/api/tags"))
                    .timeout(MODELS_TIMEOUT)
                    .GET()
                    .build();
            final HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "Ollama returned HTTP " + resp.statusCode() + " from /api/tags");
            }
            final JsonNode root = objectMapper.readTree(resp.body());
            final List<String> names = new ArrayList<>();
            final JsonNode models = root.get("models");
            if (models != null && models.isArray()) {
                for (JsonNode m : models) {
                    final JsonNode name = m.get("name");
                    if (name != null && !name.asText().isBlank()) {
                        names.add(name.asText());
                    }
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("instanceId", instance.getId());
            out.put("instanceName", instance.getName());
            out.put("models", names);
            return out;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to list models from {}: {}", baseUrl(instance), e.getMessage());
            throw new ResponseStatusException(BAD_GATEWAY,
                    "Could not reach Ollama instance \"" + instance.getName() + "\": " + e.getMessage());
        }
    }

    public record ExplainRequest(String instanceId, String model) {}

    @PostMapping("/documents/{documentId}/explain")
    public Map<String, Object> explain(@PathVariable final String documentId,
                                       @RequestBody final ExplainRequest request,
                                       final Authentication authentication) {
        if (request == null || request.instanceId() == null || request.instanceId().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "instanceId is required.");
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "model is required.");
        }

        final Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Document not found: " + documentId));
        requireDocumentAccess(authentication, document);

        final OllamaInstance instance = ollamaInstanceRepository.findById(request.instanceId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Ollama instance not found."));

        final List<Span> spans = spanRepository.findByDocumentId(documentId);
        final String prompt = buildPrompt(document, spans);

        try {
            final ObjectNode body = objectMapper.createObjectNode();
            body.put("model", request.model());
            body.put("prompt", prompt);
            body.put("stream", false);

            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl(instance) + "/api/generate"))
                    .timeout(GENERATE_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            final HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "Ollama returned HTTP " + resp.statusCode() + " from /api/generate");
            }
            final JsonNode root = objectMapper.readTree(resp.body());
            final String response = root.path("response").asText("");

            auditLogService.log("OLLAMA_EXPLAIN", "Document", documentId,
                    Map.of("instanceId", instance.getId(),
                            "instanceName", instance.getName() == null ? "" : instance.getName(),
                            "model", request.model(),
                            "spanCount", spans.size(),
                            "responseLength", response.length()));

            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("instanceName", instance.getName());
            out.put("model", request.model());
            out.put("response", response);
            return out;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to call Ollama at {}: {}", baseUrl(instance), e.getMessage());
            throw new ResponseStatusException(BAD_GATEWAY,
                    "Could not reach Ollama instance \"" + instance.getName() + "\": " + e.getMessage());
        }
    }

    @PostMapping("/spans/{spanId}/second-opinion")
    public Map<String, Object> secondOpinion(@PathVariable final String spanId,
                                             final Authentication authentication) {
        final Span span = spanRepository.findById(spanId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Span not found: " + spanId));
        final Document document = documentRepository.findById(span.getDocumentId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Document not found: " + span.getDocumentId()));
        requireDocumentAccess(authentication, document);

        final LlmJudgeDefaults defaults = llmJudgeDefaultsService.load();
        if (defaults.getSecondOpinionInstanceId() == null) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "No default Ollama instance is configured for Second Opinion. "
                            + "An administrator must set one under Admin → LLM-as-a-Judge.");
        }
        final OllamaInstance instance = ollamaInstanceRepository.findById(defaults.getSecondOpinionInstanceId())
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST,
                        "The configured Second Opinion Ollama instance no longer exists."));

        String model = defaults.getSecondOpinionModel();
        if (model == null || model.isBlank()) {
            model = pickFirstModel(instance);
        }
        final String prompt = buildSecondOpinionPrompt(span, document);

        try {
            final ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("prompt", prompt);
            body.put("stream", false);

            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl(instance) + "/api/generate"))
                    .timeout(GENERATE_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            final HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "Ollama returned HTTP " + resp.statusCode() + " from /api/generate");
            }
            final JsonNode root = objectMapper.readTree(resp.body());
            final String response = root.path("response").asText("");

            auditLogService.log("SPAN_SECOND_OPINION", "Span", span.getId(),
                    Map.of("instanceId", instance.getId(),
                            "instanceName", instance.getName() == null ? "" : instance.getName(),
                            "model", model,
                            "type", span.getType() == null ? "" : span.getType(),
                            "responseLength", response.length()));

            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("instanceName", instance.getName());
            out.put("model", model);
            out.put("sourceText", span.getText());
            out.put("sourceType", span.getType());
            out.put("response", response);
            return out;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to call Ollama at {}: {}", baseUrl(instance), e.getMessage());
            throw new ResponseStatusException(BAD_GATEWAY,
                    "Could not reach Ollama instance \"" + instance.getName() + "\": " + e.getMessage());
        }
    }

    private String pickFirstModel(final OllamaInstance instance) {
        try {
            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl(instance) + "/api/tags"))
                    .timeout(MODELS_TIMEOUT)
                    .GET()
                    .build();
            final HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "Ollama returned HTTP " + resp.statusCode() + " from /api/tags");
            }
            final JsonNode models = objectMapper.readTree(resp.body()).get("models");
            if (models == null || !models.isArray() || models.isEmpty()) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "Default Ollama instance has no models installed.");
            }
            final List<String> names = new ArrayList<>();
            for (JsonNode m : models) {
                final JsonNode name = m.get("name");
                if (name != null && !name.asText().isBlank()) names.add(name.asText());
            }
            if (names.isEmpty()) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "Default Ollama instance has no usable models.");
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names.get(0);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to query models from {}: {}", baseUrl(instance), e.getMessage());
            throw new ResponseStatusException(BAD_GATEWAY,
                    "Could not reach Ollama instance \"" + instance.getName() + "\": " + e.getMessage());
        }
    }

    private static String buildSecondOpinionPrompt(final Span span, final Document document) {
        final StringBuilder sb = new StringBuilder(4096);
        sb.append("You are reviewing a single PII redaction decision.\n\n");
        sb.append("The text \"")
                .append(span.getText() == null ? "" : span.getText())
                .append("\" was identified as PII of type \"")
                .append(span.getType() == null ? "" : span.getType())
                .append("\" by an automated redactor (confidence ")
                .append(String.format("%.2f", span.getConfidence()))
                .append(").\n\n");
        sb.append("Document context:\n");
        sb.append(document.getOriginalText() == null ? "" : document.getOriginalText());
        sb.append("\n\nIs the marked text genuinely personally identifiable information of type \"")
                .append(span.getType() == null ? "" : span.getType())
                .append("\", or might it be a false positive? Briefly explain your reasoning.\n");
        return sb.toString();
    }

    // ----- helpers -----

    private static String baseUrl(final OllamaInstance instance) {
        String host = instance.getEndpoint();
        if (host == null) host = "localhost";
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        return host + ":" + instance.getPort();
    }

    private static String buildPrompt(final Document document, final List<Span> spans) {
        final StringBuilder sb = new StringBuilder(8192);
        sb.append("You are reviewing a document for personally identifiable information (PII).\n\n");
        sb.append("Document:\n");
        sb.append(document.getOriginalText() == null ? "" : document.getOriginalText());
        sb.append("\n\nDetected PII:\n");
        if (spans == null || spans.isEmpty()) {
            sb.append("(none)\n");
        } else {
            spans.stream()
                    .sorted(Comparator.comparingInt(s -> s.getLocation() == null ? 0
                            : s.getLocation().characterStart()))
                    .forEach(s -> sb.append("- ")
                            .append(s.getType() == null ? "" : s.getType())
                            .append(": \"")
                            .append(s.getText() == null ? "" : s.getText())
                            .append("\" (confidence ")
                            .append(String.format("%.2f", s.getConfidence()))
                            .append(", status ")
                            .append(s.getStatus() == null ? "" : s.getStatus())
                            .append(")\n"));
        }
        sb.append('\n').append(EXPLAIN_PROMPT).append('\n');
        return sb.toString();
    }

    private void requireDocumentAccess(final Authentication auth, final Document document) {
        if (isAdmin(auth)) return;
        final Batch batch = document.getBatchId() == null ? null
                : batchRepository.findById(document.getBatchId()).orElse(null);
        if (batch == null || batch.getGroupId() == null) {
            throw new ResponseStatusException(NOT_FOUND, "Document not found: " + document.getId());
        }
        final Set<String> myGroupIds = userGroupsService.groupIdsForEmail(
                auth == null ? null : auth.getName());
        if (!myGroupIds.contains(batch.getGroupId())) {
            throw new ResponseStatusException(NOT_FOUND, "Document not found: " + document.getId());
        }
    }

    private static boolean isAdmin(final Authentication auth) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }
}
