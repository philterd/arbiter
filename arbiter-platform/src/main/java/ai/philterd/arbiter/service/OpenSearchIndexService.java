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

import ai.philterd.arbiter.model.Document;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class OpenSearchIndexService {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchIndexService.class);

    public static final String INDEX = "arbiter-documents";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final GeneralSettingsService generalSettingsService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public OpenSearchIndexService(final GeneralSettingsService generalSettingsService) {
        this.generalSettingsService = generalSettingsService;
    }

    /**
     * Best-effort index of the document's full text. Failures are logged but not propagated —
     * an unreachable OpenSearch should not block ingestion.
     */
    public void indexDocument(final Document document) {
        if (document == null || document.getId() == null) return;
        final String endpoint = endpoint();
        if (endpoint == null) return;

        final String url = endpoint + "/" + INDEX + "/_doc/"
                + java.net.URLEncoder.encode(document.getId(), java.nio.charset.StandardCharsets.UTF_8);
        try {
            final ObjectNode body = objectMapper.createObjectNode();
            body.put("id", document.getId());
            body.put("batchId", document.getBatchId() == null ? "" : document.getBatchId());
            body.put("filename", document.getFilename() == null ? "" : document.getFilename());
            body.put("status", document.getStatus() == null ? "" : document.getStatus());
            body.put("originalText", document.getOriginalText() == null ? "" : document.getOriginalText());

            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            final HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("OpenSearch indexing returned HTTP {} for document {} ({}): {}",
                        resp.statusCode(), document.getId(), url, truncate(resp.body()));
            }
        } catch (Exception e) {
            log.warn("OpenSearch indexing failed for document {} at {}: {}",
                    document.getId(), url, e.getMessage());
        }
    }

    public record SearchHit(String id, String batchId, String filename, String status, List<String> highlights) {}
    public record SearchResults(long total, int from, int size, List<SearchHit> hits) {}

    /**
     * Run a full-text match query over the indexed documents. Returns an empty result set if
     * OpenSearch is unreachable or returns an error — callers should not crash the request on
     * search failures.
     *
     * <p>Equivalent to calling {@link #search(String, int, int, java.util.Collection)} with
     * {@code allowedBatchIds = null}, which means "do not restrict by batch" — admin callers
     * use this form. Group-scoped callers must use the four-arg overload so the {@code total}
     * and the hit list reflect only documents the caller is permitted to see.
     */
    public SearchResults search(final String query, final int from, final int size) {
        return search(query, from, size, null);
    }

    /**
     * Same as {@link #search(String, int, int)} but with an allow-list of {@code batchId}s.
     * When {@code allowedBatchIds} is non-null, the OpenSearch query is wrapped in a
     * {@code bool} that adds a {@code terms} filter so neither the {@code total} nor the hit
     * list can disclose foreign documents to the caller. An empty allow-list short-circuits
     * to an empty result without contacting OpenSearch (a {@code terms} clause with no values
     * is rejected by some servers and would otherwise be a useless round-trip).
     */
    public SearchResults search(final String query, final int from, final int size,
                                final java.util.Collection<String> allowedBatchIds) {
        final SearchResults empty = new SearchResults(0, Math.max(0, from), Math.max(1, size), List.of());
        if (query == null || query.isBlank()) return empty;
        if (allowedBatchIds != null && allowedBatchIds.isEmpty()) return empty;
        final String endpoint = endpoint();
        if (endpoint == null) return empty;

        final int safeFrom = Math.max(0, from);
        final int safeSize = Math.max(1, Math.min(100, size));
        final String url = endpoint + "/" + INDEX + "/_search";
        try {
            final ObjectNode body = objectMapper.createObjectNode();
            body.put("from", safeFrom);
            body.put("size", safeSize);
            final ObjectNode match = objectMapper.createObjectNode();
            match.put("originalText", query);
            final ObjectNode matchClause = objectMapper.createObjectNode();
            matchClause.set("match", match);
            if (allowedBatchIds == null) {
                // Admin scope — query the index directly.
                body.set("query", matchClause);
            } else {
                // Restricted scope — wrap the match in a bool with a terms filter on batchId.
                // The total reported by OpenSearch will reflect only matching documents that
                // also fall within the caller's allow-list, so neither the count nor the hit
                // list can leak the existence of inaccessible documents.
                final ArrayNode batchIdsArr = objectMapper.createArrayNode();
                for (String id : allowedBatchIds) {
                    if (id != null && !id.isBlank()) batchIdsArr.add(id);
                }
                final ObjectNode termsBatch = objectMapper.createObjectNode();
                termsBatch.set("batchId", batchIdsArr);
                final ObjectNode termsFilter = objectMapper.createObjectNode();
                termsFilter.set("terms", termsBatch);
                final ObjectNode bool = objectMapper.createObjectNode();
                bool.set("must", matchClause);
                bool.set("filter", termsFilter);
                final ObjectNode boolQuery = objectMapper.createObjectNode();
                boolQuery.set("bool", bool);
                body.set("query", boolQuery);
            }
            // Per-request unguessable sentinels around the matched terms. After OpenSearch returns
            // the highlight, we HTML-escape the entire snippet (so any user-typed HTML in the
            // surrounding document text becomes inert text) and only then swap our sentinels
            // for <mark>...</mark>. User content cannot forge the sentinel because it embeds a
            // fresh UUID per request.
            final String openTag = "[[arbHL_" + java.util.UUID.randomUUID().toString().replace("-", "") + "_OPEN]]";
            final String closeTag = "[[arbHL_" + java.util.UUID.randomUUID().toString().replace("-", "") + "_CLOSE]]";
            final ObjectNode highlightFields = objectMapper.createObjectNode();
            highlightFields.set("originalText", objectMapper.createObjectNode());
            final ObjectNode highlight = objectMapper.createObjectNode();
            highlight.set("fields", highlightFields);
            highlight.set("pre_tags", objectMapper.createArrayNode().add(openTag));
            highlight.set("post_tags", objectMapper.createArrayNode().add(closeTag));
            body.set("highlight", highlight);

            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            final HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                // Index doesn't exist yet — no documents indexed.
                return new SearchResults(0, safeFrom, safeSize, List.of());
            }
            if (resp.statusCode() / 100 != 2) {
                log.warn("OpenSearch search returned HTTP {} for query '{}': {}",
                        resp.statusCode(), query, truncate(resp.body()));
                return empty;
            }
            final JsonNode root = objectMapper.readTree(resp.body());
            final JsonNode hitsNode = root.path("hits");
            final long total = hitsNode.path("total").path("value").asLong(0);
            final ArrayNode hitsArray = hitsNode.path("hits").isArray()
                    ? (ArrayNode) hitsNode.path("hits") : objectMapper.createArrayNode();
            final List<SearchHit> hits = new ArrayList<>();
            for (JsonNode hit : hitsArray) {
                final JsonNode source = hit.path("_source");
                final List<String> highlights = new ArrayList<>();
                final JsonNode hl = hit.path("highlight").path("originalText");
                if (hl.isArray()) {
                    for (JsonNode h : hl) highlights.add(sanitizeHighlight(h.asText(), openTag, closeTag));
                }
                hits.add(new SearchHit(
                        hit.path("_id").asText(""),
                        source.path("batchId").asText(""),
                        source.path("filename").asText(""),
                        source.path("status").asText(""),
                        Collections.unmodifiableList(highlights)));
            }
            return new SearchResults(total, safeFrom, safeSize, List.copyOf(hits));
        } catch (Exception e) {
            log.warn("OpenSearch search failed for query '{}' at {}: {}", query, url, e.getMessage());
            return empty;
        }
    }

    /**
     * Find documents in the same batch that are textually similar to the given document,
     * using OpenSearch's more_like_this query. The source document is automatically excluded
     * from the results. Returns an empty result set on any failure.
     */
    public SearchResults findSimilar(final String documentId, final String batchId, final int size) {
        final SearchResults empty = new SearchResults(0, 0, size, List.of());
        if (documentId == null || documentId.isBlank()) return empty;
        final String endpoint = endpoint();
        if (endpoint == null) return empty;

        final int safeSize = Math.max(1, Math.min(20, size));
        final String url = endpoint + "/" + INDEX + "/_search";
        try {
            final ObjectNode mlt = objectMapper.createObjectNode();
            final ArrayNode fields = objectMapper.createArrayNode();
            fields.add("originalText");
            mlt.set("fields", fields);
            final ArrayNode like = objectMapper.createArrayNode();
            final ObjectNode likeDoc = objectMapper.createObjectNode();
            likeDoc.put("_index", INDEX);
            likeDoc.put("_id", documentId);
            like.add(likeDoc);
            mlt.set("like", like);
            mlt.put("min_term_freq", 1);
            mlt.put("min_doc_freq", 1);

            final ObjectNode boolQuery = objectMapper.createObjectNode();
            boolQuery.set("must", objectMapper.createObjectNode().set("more_like_this", mlt));
            if (batchId != null && !batchId.isBlank()) {
                final ObjectNode term = objectMapper.createObjectNode();
                term.put("batchId", batchId);
                boolQuery.set("filter", objectMapper.createObjectNode().set("term", term));
            }

            final ObjectNode body = objectMapper.createObjectNode();
            body.put("size", safeSize);
            body.set("query", objectMapper.createObjectNode().set("bool", boolQuery));

            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            final HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return empty;
            if (resp.statusCode() / 100 != 2) {
                log.warn("OpenSearch findSimilar returned HTTP {} for document {}: {}",
                        resp.statusCode(), documentId, truncate(resp.body()));
                return empty;
            }
            final JsonNode root = objectMapper.readTree(resp.body());
            final JsonNode hitsNode = root.path("hits");
            final long total = hitsNode.path("total").path("value").asLong(0);
            final ArrayNode hitsArray = hitsNode.path("hits").isArray()
                    ? (ArrayNode) hitsNode.path("hits") : objectMapper.createArrayNode();
            final List<SearchHit> hits = new ArrayList<>();
            for (JsonNode hit : hitsArray) {
                final JsonNode source = hit.path("_source");
                hits.add(new SearchHit(
                        hit.path("_id").asText(""),
                        source.path("batchId").asText(""),
                        source.path("filename").asText(""),
                        source.path("status").asText(""),
                        List.of()));
            }
            return new SearchResults(total, 0, safeSize, Collections.unmodifiableList(hits));
        } catch (Exception e) {
            log.warn("OpenSearch findSimilar failed for document {} at {}: {}", documentId, url, e.getMessage());
            return empty;
        }
    }

    private String endpoint() {
        try {
            final String e = generalSettingsService.load().getOpensearchEndpoint();
            if (e == null || e.isBlank()) return null;
            return e.endsWith("/") ? e.substring(0, e.length() - 1) : e;
        } catch (Exception e) {
            log.warn("Could not load OpenSearch endpoint setting: {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(final String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    /**
     * Render a highlight snippet as safe HTML. The raw snippet contains the surrounding
     * document text (which may include user-typed {@code <script>} payloads) bracketed by
     * the per-request {@code openTag}/{@code closeTag} sentinels around matched terms. We
     * HTML-escape the whole thing first — neutralizing any inline HTML in the document text
     * — and only then swap our private sentinels for {@code <mark>...</mark>}. The sentinels
     * embed a fresh UUID per request, so user content cannot forge them.
     */
    static String sanitizeHighlight(final String raw, final String openTag, final String closeTag) {
        if (raw == null) return "";
        final String escaped = raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return escaped
                .replace(openTag, "<mark>")
                .replace(closeTag, "</mark>");
    }
}
