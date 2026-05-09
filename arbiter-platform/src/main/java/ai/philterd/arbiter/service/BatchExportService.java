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

import ai.philterd.arbiter.model.BackgroundJob;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.LocalDirectoryDestination;
import ai.philterd.arbiter.model.S3Destination;
import ai.philterd.arbiter.model.Span;
import ai.philterd.arbiter.repository.BackgroundJobRepository;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.LocalDirectoryDestinationRepository;
import ai.philterd.arbiter.repository.S3DestinationRepository;
import ai.philterd.arbiter.repository.SpanRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds an export payload for the APPROVED documents of a batch and pushes it to a
 * configured destination. Currently supports the JSONL data format (one JSON object
 * per line, see {@link JsonlExportRenderer} for the schema). Future formats will
 * plug in here without changing the destination-write or audit-logging machinery.
 *
 * <p>Exports run as {@link BackgroundJob#TYPE_BATCH_EXPORT} background jobs. The
 * controller calls {@link #enqueueExport} which validates the request and persists
 * a PENDING {@code BackgroundJob}; {@code DataExportDispatcher} later promotes the
 * job to RUNNING and calls {@link #runJob}, which streams the data to the
 * configured destination and updates the job's progress fields. The user follows
 * the work on the Background Jobs page.
 */
@Service
public class BatchExportService {

    private static final Logger LOG = LoggerFactory.getLogger(BatchExportService.class);

    /** Document status that opts a document into the export. */
    private static final String APPROVED_STATUS = "APPROVED";

    /** Page size when iterating APPROVED documents. Bounded so a 10k-doc batch
     *  doesn't materialize all rows at once on a small heap. */
    private static final int PAGE_SIZE = 200;

    /**
     * Available export formats.
     *
     * <ul>
     *   <li>{@link #JSONL} — one document per line in a single file (see
     *       {@link JsonlExportRenderer}).</li>
     *   <li>{@link #BIO} — one document per file, token-per-line BIO format
     *       (see {@link BioExportRenderer}). The destination receives a
     *       file-per-document fan-out instead of a single combined file.</li>
     * </ul>
     */
    public enum Format { JSONL, BIO }

    public enum DestinationKind { LOCAL, S3 }

    private final BatchRepository batchRepository;
    private final DocumentRepository documentRepository;
    private final SpanRepository spanRepository;
    private final LocalDirectoryDestinationRepository localDestinationRepository;
    private final S3DestinationRepository s3DestinationRepository;
    private final JsonlExportRenderer jsonlRenderer;
    private final BioExportRenderer bioRenderer;
    private final DestinationWriter destinationWriter;
    private final AuditLogService auditLogService;
    private final BackgroundJobRepository backgroundJobRepository;

    public BatchExportService(final BatchRepository batchRepository,
                              final DocumentRepository documentRepository,
                              final SpanRepository spanRepository,
                              final LocalDirectoryDestinationRepository localDestinationRepository,
                              final S3DestinationRepository s3DestinationRepository,
                              final JsonlExportRenderer jsonlRenderer,
                              final BioExportRenderer bioRenderer,
                              final DestinationWriter destinationWriter,
                              final AuditLogService auditLogService,
                              final BackgroundJobRepository backgroundJobRepository) {
        this.batchRepository = batchRepository;
        this.documentRepository = documentRepository;
        this.spanRepository = spanRepository;
        this.localDestinationRepository = localDestinationRepository;
        this.s3DestinationRepository = s3DestinationRepository;
        this.jsonlRenderer = jsonlRenderer;
        this.bioRenderer = bioRenderer;
        this.destinationWriter = destinationWriter;
        this.auditLogService = auditLogService;
        this.backgroundJobRepository = backgroundJobRepository;
    }

    /**
     * Validate the request and persist a PENDING export job. The actual export
     * work runs later, when {@code DataExportDispatcher} claims the job and calls
     * {@link #runJob}. Validation failures (no such batch, no such destination,
     * batch with zero approved documents) cause this method to return a
     * {@link Result#failure(String)} without persisting anything — the controller
     * surfaces the failure to the operator immediately rather than recording a
     * job that will fail on its first poll.
     */
    public Result enqueueExport(final String batchId,
                                final Format format,
                                final DestinationKind destinationKind,
                                final String destinationId,
                                final String actorEmail) {
        if (format == null) {
            return Result.failure("Data format is required.");
        }
        final Batch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null) {
            return Result.failure("Batch not found: " + batchId);
        }
        // Resolve the destination row up front so a typo or stale id surfaces here
        // rather than on the dispatcher tick.
        final String destinationName = lookupDestinationName(destinationKind, destinationId);
        if (destinationName == null) {
            return Result.failure(destinationKind.name() + " destination not found: " + destinationId);
        }
        // Refuse zero-document exports up front. The same check fires again at
        // run time in case the count drops to zero between enqueue and dispatch.
        final long approvedCount = documentRepository.countByBatchIdAndStatus(batchId, APPROVED_STATUS);
        if (approvedCount == 0) {
            return Result.failure("Batch \"" + batch.getName() + "\" has no APPROVED documents to export.");
        }

        final BackgroundJob job = new BackgroundJob();
        job.setId(UUID.randomUUID().toString());
        job.setType(BackgroundJob.TYPE_BATCH_EXPORT);
        job.setBatchId(batch.getId());
        job.setBatchName(batch.getName());
        job.setDestinationKind(destinationKind.name());
        job.setDestinationId(destinationId);
        job.setDestinationName(destinationName);
        job.setDataFormat(format.name());
        job.setStatus(BackgroundJob.STATUS_PENDING);
        job.setTotalDocuments(approvedCount);
        job.setCreatedAt(Instant.now());
        job.setCreatedBy(actorEmail == null ? "" : actorEmail);
        backgroundJobRepository.save(job);
        return Result.queued(job.getId(), approvedCount);
    }

    /**
     * Execute a previously-persisted PENDING export job. Invoked by
     * {@code DataExportDispatcher} after it has claimed the job (PENDING→RUNNING).
     * Renders the configured data format, ships it to the destination, and
     * updates the job's status / counters / error message. Always finishes in a
     * terminal state ({@code COMPLETED} or {@code FAILED}) — never leaves a
     * RUNNING row for the dispatcher to retry.
     */
    public void runJob(final String jobId) {
        BackgroundJob job = backgroundJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            LOG.warn("Export job {} disappeared before it could run.", jobId);
            return;
        }
        // The dispatcher already flipped status to RUNNING via findAndModify;
        // refreshing once here keeps the in-memory copy in sync with the
        // startedAt the dispatcher stamped.
        if (!BackgroundJob.STATUS_RUNNING.equals(job.getStatus())) {
            job.setStatus(BackgroundJob.STATUS_RUNNING);
            job.setStartedAt(Instant.now());
            job = backgroundJobRepository.save(job);
        }

        final Batch batch = batchRepository.findById(job.getBatchId()).orElse(null);
        if (batch == null) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Batch was deleted before the export ran.");
            return;
        }

        final Format format;
        final DestinationKind kind;
        try {
            format = Format.valueOf(job.getDataFormat());
            kind = DestinationKind.valueOf(job.getDestinationKind());
        } catch (IllegalArgumentException e) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Unknown export format or destination kind on job: " + e.getMessage());
            return;
        }

        switch (format) {
            case JSONL -> runJsonlJob(job, batch, kind);
            case BIO   -> runBioJob(job, batch, kind);
        }
    }

    /**
     * JSONL export: render every approved document into a single in-memory file
     * and ship it as one upload. Best for downstream pipelines that expect a
     * single combined artifact per export run.
     */
    private void runJsonlJob(final BackgroundJob job, final Batch batch, final DestinationKind kind) {
        final RenderedPayload rendered;
        try {
            rendered = renderJsonl(job);
        } catch (IOException e) {
            LOG.warn("Failed to render JSONL for job {}: {}", job.getId(), e.toString());
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Could not render JSONL: " + e.getMessage());
            return;
        }
        if (rendered.documentCount == 0) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "Batch \"" + batch.getName() + "\" had no exportable documents at write time.");
            return;
        }

        final String filename = filenameFor(batch, Format.JSONL);
        final DestinationWriter.Result writeResult = ship(kind, job.getDestinationId(), filename, rendered.bytes);

        final Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("batchName", batch.getName() == null ? "" : batch.getName());
        auditDetails.put("format", Format.JSONL.name());
        auditDetails.put("destinationKind", kind.name());
        auditDetails.put("destinationId", job.getDestinationId());
        auditDetails.put("documentCount", rendered.documentCount);
        auditDetails.put("bytes", rendered.bytes.length);
        auditDetails.put("filename", filename);

        if (!writeResult.isOk()) {
            auditDetails.put("error", writeResult.getError());
            auditLogService.log("BATCH_EXPORT", "Batch", job.getBatchId(), auditDetails);
            finish(job, BackgroundJob.STATUS_FAILED, writeResult.getError());
            return;
        }

        auditDetails.put("location", writeResult.getMessage());
        auditLogService.log("BATCH_EXPORT", "Batch", job.getBatchId(), auditDetails);
        finishSuccess(job, rendered.documentCount, writeResult.getMessage());
    }

    /**
     * BIO export: each approved document becomes its own {@code .bio} file.
     * The destination receives a fan-out of file-per-document writes — one
     * call to {@link #ship} per successfully-rendered document. The job's
     * counters reflect per-document progress, including write-side failures
     * (e.g. an S3 PutObject that the bucket policy rejected for one object
     * doesn't sink the export of the others).
     */
    private void runBioJob(final BackgroundJob job, final Batch batch, final DestinationKind kind) {
        final String batchSlug = slugify(batch.getName());
        long written = 0;
        long failed = 0;
        long bytes = 0;
        int page = 0;
        while (true) {
            final Page<Document> slice = documentRepository.findByBatchIdAndStatus(
                    job.getBatchId(), APPROVED_STATUS,
                    PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt")));
            if (slice == null || slice.isEmpty()) break;
            for (Document doc : slice.getContent()) {
                final List<Span> spans = spanRepository.findByDocumentId(doc.getId());
                final String body = bioRenderer.render(doc, spans);
                if (body.isEmpty()) {
                    // Document with no source text — count as failed so the operator
                    // sees the discrepancy on the Jobs page.
                    failed++;
                    recordFailureMessage(job, doc.getId(),
                            "Document has no original text to render.");
                    continue;
                }
                final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                final String filename = bioFilenameFor(batchSlug, doc);
                final DestinationWriter.Result writeResult =
                        ship(kind, job.getDestinationId(), filename, payload);
                if (!writeResult.isOk()) {
                    failed++;
                    recordFailureMessage(job, doc.getId(),
                            "Write failed: " + writeResult.getError());
                    continue;
                }
                written++;
                bytes += payload.length;
            }
            // Progress checkpoint per page so the Jobs page UI updates while
            // the export streams.
            job.setProcessedDocuments(written);
            job.setFailedDocuments(failed);
            backgroundJobRepository.save(job);
            if (!slice.hasNext()) break;
            page++;
        }

        final Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("batchName", batch.getName() == null ? "" : batch.getName());
        auditDetails.put("format", Format.BIO.name());
        auditDetails.put("destinationKind", kind.name());
        auditDetails.put("destinationId", job.getDestinationId());
        auditDetails.put("documentCount", written);
        auditDetails.put("failedDocuments", failed);
        auditDetails.put("bytes", bytes);
        auditLogService.log("BATCH_EXPORT", "Batch", job.getBatchId(), auditDetails);

        if (written == 0) {
            finish(job, BackgroundJob.STATUS_FAILED,
                    "BIO export wrote 0 documents (" + failed + " failed).");
            return;
        }
        finishSuccess(job, (int) written,
                "Wrote " + written + " BIO file(s) ("
                        + (failed > 0 ? failed + " failed; " : "")
                        + bytes + " bytes total) to "
                        + kind.name() + " destination.");
    }

    private static String bioFilenameFor(final String batchSlug, final Document doc) {
        // Prefer the original filename so the export is recognisable; fall
        // back to the document id when the upload had no filename. Always
        // prefix with the batch slug so concurrent batch exports to the same
        // destination don't collide on a generic name like "report".
        final String base = (doc.getFilename() != null && !doc.getFilename().isBlank())
                ? slugify(stripExtension(doc.getFilename()))
                : (doc.getId() == null ? "doc" : slugify(doc.getId()));
        return batchSlug + "/" + base + ".bio";
    }

    private static String stripExtension(final String name) {
        final int dot = name.lastIndexOf('.');
        if (dot <= 0) return name;
        return name.substring(0, dot);
    }

    private void recordFailureMessage(final BackgroundJob job, final String docId, final String message) {
        if (job.getFailureMessages() == null) {
            job.setFailureMessages(new java.util.ArrayList<>());
        }
        if (job.getFailureMessages().size() < BackgroundJob.MAX_FAILURE_MESSAGES) {
            job.getFailureMessages().add("Document " + docId + ": " + message);
        }
    }

    private RenderedPayload renderJsonl(final BackgroundJob job) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int written = 0;
        long failed = 0;
        try (Writer writer = new OutputStreamWriter(buffer, StandardCharsets.UTF_8)) {
            int page = 0;
            while (true) {
                final Page<Document> slice = documentRepository.findByBatchIdAndStatus(
                        job.getBatchId(), APPROVED_STATUS,
                        PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt")));
                if (slice == null || slice.isEmpty()) break;
                for (Document doc : slice.getContent()) {
                    final List<Span> spans = spanRepository.findByDocumentId(doc.getId());
                    final String line;
                    try {
                        line = jsonlRenderer.render(doc, spans);
                    } catch (JsonProcessingException e) {
                        // Skip the bad row but keep going — one corrupt document
                        // shouldn't sink the whole export. Counters bumped + a
                        // sample failure message attached so the operator can
                        // see what went wrong from the Jobs page.
                        LOG.warn("Skipping document {} in export job {}: {}",
                                doc.getId(), job.getId(), e.toString());
                        failed++;
                        recordFailureSample(job, doc.getId(), e);
                        continue;
                    }
                    writer.write(line);
                    writer.write('\n');
                    written++;
                }
                // Progress checkpoint per page so the Jobs page UI updates as we
                // go rather than only at completion.
                job.setProcessedDocuments(written);
                job.setFailedDocuments(failed);
                backgroundJobRepository.save(job);
                if (!slice.hasNext()) break;
                page++;
            }
        }
        return new RenderedPayload(buffer.toByteArray(), written);
    }

    private void recordFailureSample(final BackgroundJob job, final String docId, final Exception e) {
        if (job.getFailureMessages() == null) {
            job.setFailureMessages(new java.util.ArrayList<>());
        }
        if (job.getFailureMessages().size() < BackgroundJob.MAX_FAILURE_MESSAGES) {
            job.getFailureMessages().add("Document " + docId + ": " + e.getMessage());
        }
    }

    private void finish(final BackgroundJob job, final String status, final String errorMessage) {
        job.setStatus(status);
        job.setFinishedAt(Instant.now());
        if (errorMessage != null) job.setErrorMessage(errorMessage);
        backgroundJobRepository.save(job);
    }

    private void finishSuccess(final BackgroundJob job, final int written, final String location) {
        job.setStatus(BackgroundJob.STATUS_COMPLETED);
        job.setFinishedAt(Instant.now());
        job.setProcessedDocuments(written);
        // Store the success location in errorMessage's sibling field — we don't
        // have a dedicated success-message field, so the Jobs page surfaces the
        // location through the audit-log row instead. (Adding a free-text
        // success-message column to BackgroundJob would touch every job kind
        // for one feature; defer until a second job kind needs the same.)
        backgroundJobRepository.save(job);
        LOG.info("Export job {} completed: {} documents to {}", job.getId(), written, location);
    }

    private String lookupDestinationName(final DestinationKind kind, final String destinationId) {
        switch (kind) {
            case LOCAL: {
                return localDestinationRepository.findById(destinationId)
                        .map(LocalDirectoryDestination::getName).orElse(null);
            }
            case S3: {
                return s3DestinationRepository.findById(destinationId)
                        .map(S3Destination::getName).orElse(null);
            }
            default:
                return null;
        }
    }

    private DestinationWriter.Result ship(final DestinationKind kind,
                                          final String destinationId,
                                          final String filename,
                                          final byte[] payload) {
        switch (kind) {
            case LOCAL: {
                final Optional<LocalDirectoryDestination> opt = localDestinationRepository.findById(destinationId);
                if (opt.isEmpty()) {
                    return DestinationWriter.Result.failure("Local directory destination not found: " + destinationId);
                }
                return destinationWriter.writeLocal(opt.get(), filename, payload);
            }
            case S3: {
                final Optional<S3Destination> opt = s3DestinationRepository.findById(destinationId);
                if (opt.isEmpty()) {
                    return DestinationWriter.Result.failure("S3 destination not found: " + destinationId);
                }
                return destinationWriter.writeS3(opt.get(), filename, payload);
            }
            default:
                return DestinationWriter.Result.failure("Unsupported destination kind: " + kind);
        }
    }

    private static String filenameFor(final Batch batch, final Format format) {
        // {batch-slug}-{epochSeconds}.{ext} — a stable, sortable name that doesn't
        // collide if the same batch is exported twice in the same minute. The slug
        // is lowercased ASCII so it's safe across local FS and S3 keys.
        final String slug = slugify(batch.getName());
        final String ext = format == Format.JSONL ? "jsonl" : format.name().toLowerCase(Locale.ROOT);
        return slug + "-" + Instant.now().getEpochSecond() + "." + ext;
    }

    private static String slugify(final String s) {
        if (s == null || s.isBlank()) return "batch";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 60; i++) {
            final char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                sb.append(Character.toLowerCase(c));
            } else if (c == ' ' || c == '-' || c == '_') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') sb.append('-');
            }
        }
        if (sb.length() == 0) return "batch";
        if (sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private record RenderedPayload(byte[] bytes, int documentCount) { }

    /**
     * Outcome of {@link #enqueueExport}. Successful enqueue carries the new
     * job's id and the predicted document count so the caller can craft the
     * "queued — N documents" flash message.
     */
    public static final class Result {
        private final boolean ok;
        private final String jobId;
        private final long approvedCount;
        private final String error;

        private Result(final boolean ok, final String jobId, final long approvedCount, final String error) {
            this.ok = ok;
            this.jobId = jobId;
            this.approvedCount = approvedCount;
            this.error = error;
        }

        public static Result queued(final String jobId, final long approvedCount) {
            return new Result(true, jobId, approvedCount, null);
        }

        public static Result failure(final String error) {
            return new Result(false, null, 0, error);
        }

        public boolean isOk() { return ok; }
        public String getJobId() { return jobId; }
        public long getApprovedCount() { return approvedCount; }
        public String getError() { return error; }
    }
}
