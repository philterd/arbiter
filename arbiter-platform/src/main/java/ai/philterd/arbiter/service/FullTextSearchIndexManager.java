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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Stand-alone OpenSearch index management for the full-text search index. This service
 * is invoked by the admin Settings save-handler — it probes the configured cluster, and
 * either creates the index with the canonical mapping or compares an existing index's
 * mappings against the canonical shape so the operator can decide whether to continue.
 *
 * Kept separate from {@link OpenSearchIndexService} (which writes documents and runs
 * search queries) so the admin-side bootstrap concerns don't entangle with the
 * data-plane code path.
 */
@Service
public class FullTextSearchIndexManager {

    private static final Logger log = LoggerFactory.getLogger(FullTextSearchIndexManager.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /** Outcome states the admin Settings handler reacts to. */
    public enum Outcome {
        /** Connection succeeded, index did not exist, and we created it with the canonical mapping. */
        CREATED,
        /** Index already exists and its mapping matches the canonical shape exactly. */
        ALREADY_MATCHES,
        /** Index already exists but its mapping differs from canonical — operator must confirm. */
        MAPPING_MISMATCH,
        /** Network or auth failure reaching the cluster. */
        UNREACHABLE,
        /** OpenSearch returned a non-2xx status when we tried to inspect or create the index. */
        SERVER_ERROR
    }

    /**
     * Result of a probe / create attempt. {@link #message} carries an operator-facing
     * description; {@link #existingMappingJson} and {@link #expectedMappingJson} are
     * populated when the outcome is {@link Outcome#MAPPING_MISMATCH} so the UI can
     * render a side-by-side diff.
     */
    public record Result(Outcome outcome, String message, String existingMappingJson, String expectedMappingJson) {}

    /**
     * Probe the cluster at {@code endpoint} for an index named {@code indexName}. If the
     * index does not exist, create it with the canonical mapping. If it does exist,
     * compare its mappings against the canonical shape. {@code username} and {@code password}
     * are optional; both null/blank means "no auth header".
     */
    public Result ensureIndex(final String endpoint, final String indexName,
                              final String username, final String password) {
        final String base = trimTrailingSlash(endpoint);
        final String indexUrl = base + "/" + indexName;

        // 1. HEAD the index. 200 → exists; 404 → not exists; anything else → error.
        final HttpRequest head = builder(indexUrl, username, password)
                .timeout(REQUEST_TIMEOUT)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        final HttpResponse<String> headResp;
        try {
            headResp = httpClient.send(head, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("OpenSearch HEAD {} failed: {}", indexUrl, e.getMessage());
            return new Result(Outcome.UNREACHABLE,
                    "Could not reach OpenSearch at " + base + ": " + e.getMessage(),
                    null, null);
        }

        if (headResp.statusCode() == 200) {
            return compareMappingAgainstCanonical(base, indexName, username, password);
        }
        if (headResp.statusCode() == 404) {
            return createIndexWithCanonicalMapping(base, indexName, username, password);
        }
        return new Result(Outcome.SERVER_ERROR,
                "OpenSearch returned HTTP " + headResp.statusCode()
                        + " when checking for index '" + indexName + "'.",
                null, null);
    }

    /**
     * Create the index with the canonical mapping. Used after a {@code MAPPING_MISMATCH}
     * resolution in the admin UI when the operator chose the "delete and recreate" path,
     * or after the operator just clicks "continue" on an empty index.
     */
    public Result forceCreate(final String endpoint, final String indexName,
                              final String username, final String password) {
        return createIndexWithCanonicalMapping(trimTrailingSlash(endpoint), indexName, username, password);
    }

    /**
     * Canonical mapping for the full-text search index. The {@code _doc/_search} body
     * shape used by the runtime indexer is committed to:
     * <ul>
     *   <li>{@code id} — keyword (exact-match, primary)</li>
     *   <li>{@code batchId} — keyword (group-restricted filter on search)</li>
     *   <li>{@code filename} — keyword</li>
     *   <li>{@code status} — keyword</li>
     *   <li>{@code originalText} — text (analyzed for full-text match + highlight)</li>
     * </ul>
     * Returned as a serialized JSON string so the comparison against an existing index's
     * mappings can be a textual equality check after canonicalisation.
     */
    public String canonicalMappingJson() {
        try {
            return objectMapper.writeValueAsString(canonicalMappingTree());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize canonical mapping", e);
        }
    }

    private JsonNode canonicalMappingTree() {
        final ObjectNode props = objectMapper.createObjectNode();
        props.set("id", textOrKeyword("keyword"));
        props.set("batchId", textOrKeyword("keyword"));
        props.set("filename", textOrKeyword("keyword"));
        props.set("status", textOrKeyword("keyword"));
        props.set("originalText", textOrKeyword("text"));
        final ObjectNode mappings = objectMapper.createObjectNode();
        mappings.set("properties", props);
        return mappings;
    }

    private ObjectNode textOrKeyword(final String type) {
        final ObjectNode field = objectMapper.createObjectNode();
        field.put("type", type);
        return field;
    }

    private Result compareMappingAgainstCanonical(final String base, final String indexName,
                                                  final String username, final String password) {
        final String mappingUrl = base + "/" + indexName + "/_mapping";
        final HttpRequest get = builder(mappingUrl, username, password)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            final HttpResponse<String> resp = httpClient.send(get, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return new Result(Outcome.SERVER_ERROR,
                        "OpenSearch returned HTTP " + resp.statusCode()
                                + " fetching the mapping for '" + indexName + "'.",
                        null, null);
            }
            final JsonNode root = objectMapper.readTree(resp.body());
            // Response shape: {"<indexName>": {"mappings": {...}}}
            final JsonNode existing = root.path(indexName).path("mappings");
            final JsonNode expected = canonicalMappingTree();
            if (mappingsEqual(existing, expected)) {
                return new Result(Outcome.ALREADY_MATCHES,
                        "Index '" + indexName + "' already exists with the expected mapping.",
                        null, null);
            }
            return new Result(Outcome.MAPPING_MISMATCH,
                    "Index '" + indexName + "' exists but its mapping differs from the expected shape.",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(existing),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(expected));
        } catch (Exception e) {
            log.warn("OpenSearch GET {} failed: {}", mappingUrl, e.getMessage());
            return new Result(Outcome.UNREACHABLE,
                    "Could not fetch mapping from " + base + ": " + e.getMessage(),
                    null, null);
        }
    }

    private Result createIndexWithCanonicalMapping(final String base, final String indexName,
                                                   final String username, final String password) {
        final String indexUrl = base + "/" + indexName;
        try {
            final ObjectNode body = objectMapper.createObjectNode();
            body.set("mappings", canonicalMappingTree());
            final HttpRequest put = builder(indexUrl, username, password)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            final HttpResponse<String> resp = httpClient.send(put, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return new Result(Outcome.SERVER_ERROR,
                        "Could not create index '" + indexName + "': OpenSearch returned HTTP "
                                + resp.statusCode() + " - " + truncate(resp.body()),
                        null, null);
            }
            return new Result(Outcome.CREATED,
                    "Index '" + indexName + "' created with the full-text search mapping.",
                    null, null);
        } catch (Exception e) {
            log.warn("OpenSearch PUT {} failed: {}", indexUrl, e.getMessage());
            return new Result(Outcome.UNREACHABLE,
                    "Could not reach OpenSearch at " + base + ": " + e.getMessage(),
                    null, null);
        }
    }

    /**
     * Compare two mapping trees focusing only on the fields Arbiter cares about. The
     * existing mapping may carry extra fields (Arbiter doesn't object to that) but every
     * field in the canonical shape must be present with the canonical type. Other
     * differences (custom analyzers, additional properties) are tolerated; missing
     * fields or type mismatches on canonical fields fail the comparison.
     */
    private boolean mappingsEqual(final JsonNode existing, final JsonNode expected) {
        if (existing == null || existing.isMissingNode()) return false;
        final JsonNode existingProps = existing.path("properties");
        final JsonNode expectedProps = expected.path("properties");
        if (!expectedProps.isObject() || !existingProps.isObject()) return false;
        final java.util.Iterator<String> names = expectedProps.fieldNames();
        while (names.hasNext()) {
            final String field = names.next();
            final JsonNode expectedField = expectedProps.get(field);
            final JsonNode existingField = existingProps.get(field);
            if (existingField == null || existingField.isMissingNode()) {
                return false;
            }
            final String expectedType = expectedField.path("type").asText("");
            final String existingType = existingField.path("type").asText("");
            if (!expectedType.equals(existingType)) {
                return false;
            }
        }
        return true;
    }

    private static HttpRequest.Builder builder(final String url, final String username, final String password) {
        final HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url));
        if (username != null && !username.isBlank() && password != null) {
            // Basic auth — the username/password pair is what OpenSearch's security plugin
            // expects. Sent over HTTPS in production; over plain HTTP it's no worse than
            // the cluster itself being open.
            final String token = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            b.header("Authorization", "Basic " + token);
        }
        return b;
    }

    private static String trimTrailingSlash(final String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(final String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
