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
import ai.philterd.arbiter.service.InboxService;
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
    private final InboxService inboxService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public OpenSearchIngestJobService(final BackgroundJobRepository jobRepository,
                                      final OpenSearchDataSourceRepository dataSourceRepository,
                                      final BatchRepository batchRepository,
                                      final DocumentRepository documentRepository,
                                      final IngestQueueService ingestQueueService,
                                      final ObjectMapper objectMapper,
                                      final SymmetricCipher cipher,
                                      final InboxService inboxService) {
        this.jobRepository = jobRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.ingestQueueService = ingestQueueService;
        this.objectMapper = objectMapper;
        this.cipher = cipher;
        this.inboxService = inboxService;
    }

    /**
     * Persist a PENDING job. Returns the saved job so the caller can redirect to the
     * Background Jobs page or include the id in a flash message.
     *
     * <p>The actual work is dispatched separately by {@code DataImportDispatcher},
     * which polls for PENDING data-import jobs and atomically promotes one per batch
     * to RUNNING. Users can queue any number of imports per batch — they execute
     * one at a time per batch in oldest-first order.
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
        final BackgroundJob saved = jobRepository.save(job);
        notifyOwner(saved);
        return saved;
    }

    /**
     * Execute a previously-persisted PENDING job. Invoked by the data-import dispatcher
     * after it has atomically claimed the job. Idempotent w.r.t. RUNNING — calling this
     * on a job that's already RUNNING is harmless.
     */
    public void run(final String jobId) {
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
                job.setTotalDocuments(job.getProcessedDocuments()
                        + job.getFailedDocuments() + job.getSkippedDocuments());
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
            final String sourceDocIdValue = hit.path("_id").asText("");

            // Dedup: if a Document with the same (sourceIndex, sourceDocId) already exists,
            // record a SKIPPED placeholder so the import attempt is auditable but don't
            // re-enqueue the content.
            if (!sourceDocIdValue.isEmpty()
                    && documentRepository.existsBySourceIndexAndSourceDocId(hitIndex, sourceDocIdValue)) {
                recordSkipped(job, batch, filename, hitIndex, sourceDocIdValue, sourceUrl, "OPENSEARCH");
                continue;
            }

            try {
                final ai.philterd.arbiter.model.Document doc =
                        ingestQueueService.enqueueText(batch, filename, text, job.getPriority());
                doc.setSourceSystem("OPENSEARCH");
                doc.setSourceUrl(sourceUrl);
                doc.setSourceIndex(hitIndex);
                doc.setSourceDocId(sourceDocIdValue);
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
     * Persist a SKIPPED placeholder Document row for a hit that's already been imported
     * (matched by source index + source doc id). The row carries the source attribution
     * so the audit trail points back to the OpenSearch hit, but no content — the existing
     * Document holds it.
     */
    private void recordSkipped(final BackgroundJob job, final Batch batch, final String filename,
                               final String hitIndex, final String hitId, final String sourceUrl,
                               final String sourceSystem) {
        final ai.philterd.arbiter.model.Document doc = new ai.philterd.arbiter.model.Document();
        doc.setId(UUID.randomUUID().toString());
        doc.setBatchId(batch.getId());
        doc.setFilename(filename);
        doc.setOriginalText("");
        doc.setSourceSystem(sourceSystem);
        doc.setSourceUrl(sourceUrl);
        doc.setSourceIndex(hitIndex);
        doc.setSourceDocId(hitId);
        doc.setImportedAt(java.time.LocalDateTime.now());
        doc.setCreatedAt(java.time.LocalDateTime.now());
        doc.setPriority(job.getPriority());
        doc.changeStatus("SKIPPED");
        documentRepository.save(doc);
        bumpSkipped(job);
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

    private void bumpSkipped(final BackgroundJob job) {
        job.setSkippedDocuments(job.getSkippedDocuments() + 1);
        jobRepository.save(job);
    }

    private void finish(final BackgroundJob job, final String status, final String error) {
        job.setStatus(status);
        if (error != null) job.setErrorMessage(error);
        job.setFinishedAt(Instant.now());
        jobRepository.save(job);
        notifyOwner(job);
    }

    /**
     * Drop a one-line summary into the starting user's inbox so they don't have to keep an
     * eye on the Background Jobs page. A missing email (e.g. demo data, system-driven jobs)
     * silently skips the notification — {@link InboxService#sendByEmail} also no-ops if the
     * email doesn't resolve to a user.
     */
    private void notifyOwner(final BackgroundJob job) {
        final String email = job.getCreatedBy();
        if (email == null || email.isBlank()) return;
        inboxService.sendByEmail(email, summarize(job));
    }

    private static String summarize(final BackgroundJob job) {
        final String source = job.getSourceName() == null || job.getSourceName().isBlank()
                ? "(unknown source)" : job.getSourceName();
        final String batch = job.getBatchName() == null || job.getBatchName().isBlank()
                ? "(unknown batch)" : job.getBatchName();
        final boolean ok = BackgroundJob.STATUS_COMPLETED.equals(job.getStatus());
        final StringBuilder sb = new StringBuilder();
        sb.append("OpenSearch import from \"").append(source).append("\" into batch \"").append(batch);
        if (ok) {
            sb.append("\" completed. Processed ").append(job.getProcessedDocuments());
            if (job.getTotalDocuments() >= 0) {
                sb.append(" of ").append(job.getTotalDocuments());
            }
            sb.append(" document(s)");
            if (job.getFailedDocuments() > 0) {
                sb.append(", ").append(job.getFailedDocuments()).append(" failed");
            }
            if (job.getSkippedDocuments() > 0) {
                sb.append(", ").append(job.getSkippedDocuments()).append(" skipped (already imported)");
            }
            sb.append('.');
        } else {
            sb.append("\" failed.");
            if (job.getErrorMessage() != null && !job.getErrorMessage().isBlank()) {
                sb.append(' ').append(job.getErrorMessage());
                if (!job.getErrorMessage().endsWith(".")) sb.append('.');
            }
            if (job.getProcessedDocuments() > 0 || job.getFailedDocuments() > 0) {
                sb.append(" Processed ").append(job.getProcessedDocuments());
                if (job.getFailedDocuments() > 0) {
                    sb.append(", ").append(job.getFailedDocuments()).append(" failed");
                }
                sb.append(" before stopping.");
            }
        }
        return sb.toString();
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
