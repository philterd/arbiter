/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.services;

import ai.philterd.arbiter.model.BackgroundJob;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.OpenSearchDataSource;
import ai.philterd.arbiter.repository.BackgroundJobRepository;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.OpenSearchDataSourceRepository;
import ai.philterd.arbiter.service.SymmetricCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs OpenSearch ingest jobs on a background thread. The user clicks
 * "Ingest from OpenSearch", we record a {@link BackgroundJob}, return immediately, and the
 * worker loop walks the query results, pushing each hit's text-field value onto the
 * Arbiter ingest queue.
 */
@Service
public class OpenSearchIngestJobService {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchIngestJobService.class);

    /** OpenSearch hits returned per scroll page. Fixed; the user's saved query cannot raise it. */
    static final int PAGE_SIZE = 100;
    /** Hard ceiling on scroll iterations to defend against runaway scroll contexts. */
    private static final int MAX_PAGES = 100_000;
    /** Scroll context lifetime, refreshed on every scroll request. */
    private static final String SCROLL_KEEPALIVE = "1m";

    private final BackgroundJobRepository jobRepository;
    private final OpenSearchDataSourceRepository dataSourceRepository;
    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final IngestQueueService ingestQueueService;
    private final ObjectMapper objectMapper;
    private final SymmetricCipher cipher;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "opensearch-ingest");
        t.setDaemon(true);
        return t;
    });

    public OpenSearchIngestJobService(final BackgroundJobRepository jobRepository,
                                      final OpenSearchDataSourceRepository dataSourceRepository,
                                      final BatchRepository batchRepository,
                                      final DocumentRepository documentRepository,
                                      final IngestQueueService ingestQueueService,
                                      final ObjectMapper objectMapper,
                                      final SymmetricCipher cipher) {
        this.jobRepository = jobRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.ingestQueueService = ingestQueueService;
        this.objectMapper = objectMapper;
        this.cipher = cipher;
    }

    /**
     * Persist a PENDING job and submit it to the executor. Returns the saved job so the caller
     * can redirect to the Background Jobs page or include the id in a flash message.
     */
    public BackgroundJob start(final String sourceId, final String batchId, final int priority,
                               final String actorEmail) {
        final OpenSearchDataSource source = dataSourceRepository.findById(sourceId).orElse(null);
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (source == null) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "OpenSearch data source not found.");
        }
        if (batch == null) {
            return failImmediate(sourceId, batchId, priority, actorEmail,
                    "Batch not found.");
        }

        final BackgroundJob job = new BackgroundJob();
        job.setId(UUID.randomUUID().toString());
        job.setType(BackgroundJob.TYPE_OPENSEARCH_INGEST);
        job.setSourceId(source.getId());
        job.setSourceName(source.getName());
        job.setBatchId(batch.getId());
        job.setBatchName(batch.getName());
        job.setPriority(priority);
        job.setStatus(BackgroundJob.STATUS_PENDING);
        job.setCreatedAt(Instant.now());
        job.setCreatedBy(actorEmail == null ? "" : actorEmail);
        jobRepository.save(job);

        executor.submit(() -> run(job.getId()));
        return job;
    }

    private BackgroundJob failImmediate(final String sourceId, final String batchId, final int priority,
                                        final String actorEmail, final String error) {
        final BackgroundJob job = new BackgroundJob();
        job.setId(UUID.randomUUID().toString());
        job.setType(BackgroundJob.TYPE_OPENSEARCH_INGEST);
        job.setSourceId(sourceId);
        job.setBatchId(batchId);
        job.setPriority(priority);
        job.setStatus(BackgroundJob.STATUS_FAILED);
        job.setErrorMessage(error);
        final Instant now = Instant.now();
        job.setCreatedAt(now);
        job.setStartedAt(now);
        job.setFinishedAt(now);
        job.setCreatedBy(actorEmail == null ? "" : actorEmail);
        return jobRepository.save(job);
    }

    private void run(final String jobId) {
        BackgroundJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Background job {} disappeared before it could run.", jobId);
            return;
        }
        job.setStatus(BackgroundJob.STATUS_RUNNING);
        job.setStartedAt(Instant.now());
        jobRepository.save(job);

        final OpenSearchDataSource source = dataSourceRepository.findById(job.getSourceId()).orElse(null);
        final Batch batch = batchRepository.findById(job.getBatchId()).orElse(null);
        if (source == null || batch == null) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Source or batch was deleted before the job ran.");
            return;
        }

        final String textField = source.getTextField() == null ? "" : source.getTextField();
        if (textField.isBlank()) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Data source has no Text field configured.");
            return;
        }

        // Default index from the data source's query path (e.g. "contracts/_search" → "contracts").
        // Each hit also reports its own _index, which we prefer when present so that aliased
        // or wildcard queries record the actual index the document came from.
        final String defaultIndex = parseQueryIndex(source.getQuery());
        final String endpoint = source.getEndpoint();
        final String authHeader = basicAuthHeader(source);

        String scrollId = null;
        try {
            // Page 1: open a scroll context with size=100 so we get bounded pages.
            final JsonNode firstPage = executeInitialQuery(source, PAGE_SIZE, authHeader);
            if (firstPage == null) {
                finish(job, BackgroundJob.STATUS_FAILED, "Empty response from OpenSearch.");
                return;
            }
            final long total = firstPage.path("hits").path("total").path("value").asLong(
                    firstPage.path("hits").path("total").asLong(-1));
            if (total >= 0) {
                job.setTotalDocuments(total);
                jobRepository.save(job);
            }

            scrollId = firstPage.path("_scroll_id").asText(null);
            JsonNode hits = firstPage.path("hits").path("hits");
            int pageNumber = 1;
            int hitsThisPage = hits.isArray() ? hits.size() : 0;
            log.info("Job {}: page {} returned {} hits (scroll={}).",
                    job.getId(), pageNumber, hitsThisPage, scrollId != null);
            processHits(hits, job, source, batch, textField, defaultIndex, endpoint);

            // Subsequent pages: scroll until we get back zero hits (or the server returns no
            // more scroll context). We bound the loop generously to defend against pathological
            // servers; in practice the loop exits via the empty-page check.
            while (scrollId != null && hitsThisPage > 0 && pageNumber < MAX_PAGES) {
                final JsonNode page = executeScroll(endpoint, authHeader, scrollId);
                if (page == null) break;
                scrollId = page.path("_scroll_id").asText(scrollId);
                hits = page.path("hits").path("hits");
                hitsThisPage = hits.isArray() ? hits.size() : 0;
                pageNumber++;
                log.info("Job {}: page {} returned {} hits.", job.getId(), pageNumber, hitsThisPage);
                if (hitsThisPage == 0) break;
                processHits(hits, job, source, batch, textField, defaultIndex, endpoint);
            }

            // If the response did not advertise hits.total.value, set total to whatever we counted.
            if (job.getTotalDocuments() < 0) {
                job.setTotalDocuments(job.getProcessedDocuments() + job.getFailedDocuments());
                jobRepository.save(job);
            }
            finish(job, BackgroundJob.STATUS_COMPLETED, null);
        } catch (Exception e) {
            log.warn("Job {} failed: {}", job.getId(), e.getMessage(), e);
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Could not run OpenSearch ingest: " + e.getMessage());
        } finally {
            // Best-effort cleanup of the scroll context. A failure here is logged but does
            // not change the job status — by this point the user-visible work is done.
            if (scrollId != null) {
                try {
                    closeScroll(endpoint, authHeader, scrollId);
                } catch (Exception e) {
                    log.debug("Job {}: failed to close scroll context: {}", job.getId(), e.getMessage());
                }
            }
        }
    }

    private void processHits(final JsonNode hits, final BackgroundJob job,
                             final OpenSearchDataSource source, final Batch batch,
                             final String textField, final String defaultIndex, final String sourceUrl) {
        if (!hits.isArray()) return;
        int index = 0;
        for (Iterator<JsonNode> it = hits.elements(); it.hasNext(); index++) {
            final JsonNode hit = it.next();
            final JsonNode sourceNode = hit.path("_source");
            final JsonNode textNode = sourceNode.path(textField);
            final String hitId = hit.path("_id").asText("hit-" + index);
            if (textNode.isMissingNode() || textNode.isNull()) {
                final String reason = "Hit " + hitId + " has no '" + textField + "' field in _source.";
                log.warn("Job {}: {}", job.getId(), reason);
                bumpFailed(job, reason);
                continue;
            }
            final String text = textNode.isTextual() ? textNode.asText() : textNode.toString();
            final String filename = resolveFilename(sourceNode, source.getFilenameField(), hitId);
            final String hitIndex = hit.path("_index").isMissingNode()
                    ? defaultIndex
                    : hit.path("_index").asText(defaultIndex);
            try {
                final ai.philterd.arbiter.model.Document doc =
                        ingestQueueService.enqueueText(batch, filename, text, job.getPriority());
                doc.setSourceSystem("OPENSEARCH");
                doc.setSourceUrl(sourceUrl);
                doc.setSourceIndex(hitIndex);
                doc.setSourceDocId(hit.path("_id").asText(""));
                doc.setImportedAt(java.time.LocalDateTime.now());
                documentRepository.save(doc);
                bumpProcessed(job);
            } catch (Exception e) {
                final String reason = "Failed to enqueue hit " + hitId + ": " + e.getMessage();
                log.warn("Job {}: {}", job.getId(), reason, e);
                bumpFailed(job, reason);
            }
        }
    }

    /**
     * Open a scrolling search against the data source's configured query. The body is parsed,
     * its {@code size} field is overridden to {@code pageSize} so each scroll page is bounded,
     * and the request is sent with {@code ?scroll=1m} so we can fetch subsequent pages with
     * {@link #executeScroll(String, String, String)}.
     */
    private JsonNode executeInitialQuery(final OpenSearchDataSource source, final int pageSize,
                                         final String authHeader) throws Exception {
        final String endpoint = source.getEndpoint() == null ? "" : source.getEndpoint().trim();
        final String query = source.getQuery() == null ? "" : source.getQuery().trim();
        if (endpoint.isEmpty() || query.isEmpty()) {
            throw new IllegalStateException("Endpoint and query are both required.");
        }
        final int split = firstWhitespace(query);
        final String path = split < 0 ? query : query.substring(0, split);
        final String rawBody = split < 0 ? "{}" : query.substring(split + 1).trim();
        final String body = withSize(rawBody, pageSize);
        final String url = endpoint.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "")
                + (path.contains("?") ? "&" : "?") + "scroll=" + SCROLL_KEEPALIVE;
        return sendJson(url, body, authHeader);
    }

    /** Fetch the next page of an in-progress scroll. */
    private JsonNode executeScroll(final String endpoint, final String authHeader,
                                   final String scrollId) throws Exception {
        if (endpoint == null || scrollId == null || scrollId.isEmpty()) return null;
        final String url = endpoint.replaceAll("/+$", "") + "/_search/scroll";
        final java.util.Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("scroll", SCROLL_KEEPALIVE);
        body.put("scroll_id", scrollId);
        return sendJson(url, objectMapper.writeValueAsString(body), authHeader);
    }

    /** Best-effort: free the scroll context server-side once we're done with it. */
    private void closeScroll(final String endpoint, final String authHeader,
                             final String scrollId) throws Exception {
        if (endpoint == null || scrollId == null || scrollId.isEmpty()) return;
        final String url = endpoint.replaceAll("/+$", "") + "/_search/scroll";
        final java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("scroll_id", java.util.List.of(scrollId));
        final HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (authHeader != null) reqBuilder.header("Authorization", authHeader);
        httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.discarding());
    }

    private JsonNode sendJson(final String url, final String body, final String authHeader) throws Exception {
        final HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (authHeader != null) reqBuilder.header("Authorization", authHeader);
        final HttpResponse<String> resp = httpClient.send(reqBuilder.build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("OpenSearch returned HTTP " + resp.statusCode()
                    + (resp.body() == null || resp.body().isEmpty() ? "" : ": " + resp.body()));
        }
        return objectMapper.readTree(resp.body());
    }

    private String basicAuthHeader(final OpenSearchDataSource source) {
        final String username = source.getUsername();
        if (username == null || username.isBlank()) return null;
        final String password = source.getEncryptedPassword() == null
                || source.getEncryptedPassword().isEmpty()
                ? ""
                : cipher.decrypt(source.getEncryptedPassword());
        final String creds = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parse {@code rawBody} as a JSON object and force its {@code size} to {@code pageSize}.
     * Used so the user's saved query body cannot bypass the per-page cap. If the body isn't a
     * JSON object we send a minimal {@code {"size": N}} body and let the server reject it on
     * its own terms.
     */
    String withSize(final String rawBody, final int pageSize) {
        try {
            final JsonNode parsed = (rawBody == null || rawBody.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(rawBody);
            if (parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
                obj.put("size", pageSize);
                return objectMapper.writeValueAsString(obj);
            }
        } catch (Exception ignored) {
            // fall through to fallback
        }
        return "{\"size\":" + pageSize + "}";
    }

    private void bumpProcessed(final BackgroundJob job) {
        job.setProcessedDocuments(job.getProcessedDocuments() + 1);
        jobRepository.save(job);
    }

    private void bumpFailed(final BackgroundJob job, final String reason) {
        job.setFailedDocuments(job.getFailedDocuments() + 1);
        if (reason != null && !reason.isBlank()
                && job.getFailureMessages().size() < BackgroundJob.MAX_FAILURE_MESSAGES) {
            job.getFailureMessages().add(reason);
        }
        jobRepository.save(job);
    }

    private void finish(final BackgroundJob job, final String status, final String error) {
        job.setStatus(status);
        if (error != null) job.setErrorMessage(error);
        job.setFinishedAt(Instant.now());
        jobRepository.save(job);
    }

    private static int firstWhitespace(final String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    /**
     * Pick the filename to assign to an imported document. If the data source has a
     * {@code filenameField} configured and the hit's {@code _source} carries a non-blank
     * value at that path, that value is used; otherwise the hit's OpenSearch {@code _id}
     * is used directly (matching the user-visible behavior the importer documents).
     */
    static String resolveFilename(final JsonNode sourceNode, final String filenameField,
                                  final String hitId) {
        if (filenameField != null && !filenameField.isBlank() && sourceNode != null) {
            final JsonNode node = sourceNode.path(filenameField);
            if (!node.isMissingNode() && !node.isNull()) {
                final String value = node.isTextual() ? node.asText() : node.toString();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return hitId;
    }

    /**
     * Extract the index name from a query like {@code contracts/_search { ... }}. Returns the
     * substring before the first {@code /} on the path (so {@code contracts} for the example,
     * empty when the path doesn't have a slash). Used as the default {@code sourceIndex} when
     * a hit doesn't carry its own {@code _index} field.
     */
    static String parseQueryIndex(final String query) {
        if (query == null) return "";
        final String trimmed = query.trim();
        if (trimmed.isEmpty()) return "";
        final int ws = firstWhitespace(trimmed);
        final String path = ws < 0 ? trimmed : trimmed.substring(0, ws);
        final String stripped = path.replaceAll("^/+", "");
        final int slash = stripped.indexOf('/');
        return slash < 0 ? stripped : stripped.substring(0, slash);
    }
}
