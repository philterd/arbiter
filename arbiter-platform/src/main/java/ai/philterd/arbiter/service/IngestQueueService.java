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

import ai.philterd.arbiter.core.model.RedactionResponse;
import ai.philterd.arbiter.model.Batch;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.model.PendingUpload;
import ai.philterd.arbiter.repository.BatchRepository;
import ai.philterd.arbiter.repository.DocumentRepository;
import ai.philterd.arbiter.repository.PendingUploadRepository;
import ai.philterd.arbiter.service.RedactionService;
import ai.philterd.arbiter.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the redaction queue: callers enqueue uploads with {@link #enqueueText} or
 * {@link #enqueueFile}, which persist a {@code PENDING} {@link Document} and (for binary uploads)
 * a {@link PendingUpload} sidecar holding the raw bytes. A scheduled worker drains the queue
 * oldest-first by calling Philter and applying the response via {@link RedactionPersistenceService}.
 */
@Service
public class IngestQueueService {

    private static final Logger log = LoggerFactory.getLogger(IngestQueueService.class);

    private static final int BATCH_SIZE = 5;

    /** A document stuck in PROCESSING longer than this is assumed orphaned by a dead worker. */
    private static final Duration STUCK_CLAIM_TTL = Duration.ofMinutes(10);

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";

    private final DocumentRepository documentRepository;
    private final PendingUploadRepository pendingUploadRepository;
    private final BatchRepository batchRepository;
    private final RedactionService redactionService;
    private final RedactionPersistenceService persistenceService;
    private final MongoOperations mongoOperations;
    private final AtomicBoolean processing = new AtomicBoolean(false);

    public IngestQueueService(final DocumentRepository documentRepository,
                              final PendingUploadRepository pendingUploadRepository,
                              final BatchRepository batchRepository,
                              final RedactionService redactionService,
                              final RedactionPersistenceService persistenceService,
                              final MongoOperations mongoOperations) {
        this.documentRepository = documentRepository;
        this.pendingUploadRepository = pendingUploadRepository;
        this.batchRepository = batchRepository;
        this.redactionService = redactionService;
        this.persistenceService = persistenceService;
        this.mongoOperations = mongoOperations;
    }

    /**
     * Enqueue a text document. The text is stored on the {@code Document} itself —
     * no sidecar is needed. Returns the persisted document with status {@code PENDING}.
     */
    public Document enqueueText(final Batch batch, final String filename, final String text, final int priority) {
        final Document doc = new Document();
        doc.setId(UUID.randomUUID().toString());
        doc.setBatchId(batch.getId());
        doc.setFilename(filename);
        doc.setOriginalText(text == null ? "" : text);
        doc.setContentSha512(Hashing.sha512Hex(text == null ? "" : text));
        doc.setCreatedAt(LocalDateTime.now());
        doc.setPriority(priority);
        doc.changeStatus("PENDING");
        documentRepository.save(doc);
        return doc;
    }

    /**
     * Enqueue a binary upload (PDF or otherwise). The bytes are stored in a sidecar
     * collection because we don't yet have the extracted text — Philter will produce
     * it during async processing.
     */
    public Document enqueueFile(final Batch batch, final String filename,
                                final byte[] bytes, final String contentType, final int priority) {
        final Document doc = new Document();
        doc.setId(UUID.randomUUID().toString());
        doc.setBatchId(batch.getId());
        doc.setFilename(filename);
        doc.setContentSha512(Hashing.sha512Hex(bytes == null ? new byte[0] : bytes));
        doc.setCreatedAt(LocalDateTime.now());
        doc.setPriority(priority);
        doc.changeStatus("PENDING");
        documentRepository.save(doc);

        final PendingUpload pu = new PendingUpload();
        pu.setId(doc.getId());
        pu.setContentType(contentType == null ? "" : contentType);
        pu.setContent(bytes == null ? new byte[0] : bytes);
        pu.setCreatedAt(LocalDateTime.now());
        pendingUploadRepository.save(pu);
        return doc;
    }

    /**
     * Drain up to {@link #BATCH_SIZE} pending documents, oldest first. Each candidate is claimed via
     * an atomic {@code findAndModify} that flips {@code PENDING → PROCESSING} only if no other
     * worker has already taken it; the loser sees a {@code null} return and silently skips. The
     * in-process flag avoids overlapping ticks within a single JVM, but the Mongo-level claim is
     * what actually serializes work across multiple replicas.
     */
    @Scheduled(fixedDelayString = "${arbiter.ingest-queue.poll-millis:5000}",
               initialDelayString = "${arbiter.ingest-queue.initial-delay-millis:5000}")
    public void drain() {
        if (!processing.compareAndSet(false, true)) return;
        try {
            recoverStuckClaims();

            final List<Document> candidates = documentRepository.findByStatus(STATUS_PENDING,
                    PageRequest.of(0, BATCH_SIZE, Sort.by(Sort.Direction.ASC, "createdAt")))
                    .getContent();
            for (Document candidate : candidates) {
                final Document claimed = claim(candidate.getId());
                if (claimed == null) {
                    // Another worker beat us to this one. That's fine — keep going.
                    continue;
                }
                processOne(claimed);
            }
        } catch (Exception e) {
            log.warn("Ingest queue drain failed: {}", e.getMessage());
        } finally {
            processing.set(false);
        }
    }

    /**
     * Atomically transition a document from PENDING to PROCESSING. Returns the post-update document
     * if this caller won the race, or {@code null} if some other worker already claimed it (or the
     * document is no longer PENDING for any other reason).
     */
    Document claim(final String documentId) {
        if (mongoOperations == null) {
            // Defensive fallback for tests where MongoOperations isn't wired. Best-effort only.
            final Document doc = documentRepository.findById(documentId).orElse(null);
            if (doc == null || !STATUS_PENDING.equals(doc.getStatus())) return null;
            doc.changeStatus(STATUS_PROCESSING);
            documentRepository.save(doc);
            return doc;
        }
        final Query q = Query.query(Criteria.where("_id").is(documentId)
                .and("status").is(STATUS_PENDING));
        final Update u = new Update()
                .set("status", STATUS_PROCESSING)
                .set("statusChangedAt", LocalDateTime.now());
        return mongoOperations.findAndModify(q, u,
                FindAndModifyOptions.options().returnNew(true), Document.class);
    }

    /**
     * Reset documents stuck in PROCESSING for longer than {@link #STUCK_CLAIM_TTL} back to PENDING
     * so the next drain can pick them up. This recovers from a worker dying mid-claim — the original
     * worker's lease has expired and the work is fair game again.
     */
    private void recoverStuckClaims() {
        if (mongoOperations == null) return;
        final LocalDateTime cutoff = LocalDateTime.now().minus(STUCK_CLAIM_TTL);
        final Query q = Query.query(Criteria.where("status").is(STATUS_PROCESSING)
                .and("statusChangedAt").lt(cutoff));
        final Update u = new Update()
                .set("status", STATUS_PENDING)
                .set("statusChangedAt", LocalDateTime.now());
        try {
            final long n = mongoOperations.updateMulti(q, u, Document.class).getModifiedCount();
            if (n > 0) {
                log.info("Reclaimed {} stuck PROCESSING document(s) older than {}", n, STUCK_CLAIM_TTL);
            }
        } catch (Exception e) {
            log.warn("Stuck-claim recovery failed: {}", e.getMessage());
        }
    }

    public void processOne(final Document doc) {
        try {
            final Batch batch = batchRepository.findById(doc.getBatchId()).orElse(null);
            if (batch == null) {
                final String reason = "Batch " + doc.getBatchId() + " no longer exists";
                log.warn("Document {}: {}; marking FAILED", doc.getId(), reason);
                markFailed(doc, reason, null);
                pendingUploadRepository.deleteById(doc.getId());
                return;
            }
            final RedactionResponse response = runRedaction(doc, batch);
            // RedactionPersistenceService.apply() sets the terminal/auto-approved/review-required
            // status, which moves the document out of PROCESSING.
            persistenceService.apply(doc, batch, response);
            pendingUploadRepository.deleteById(doc.getId());
        } catch (Exception e) {
            // The document id alone is enough to correlate to the encrypted-at-rest
            // Document row. Filenames in this product routinely carry PII
            // (e.g. mrn-12345678-jane-doe-discharge.pdf, 2024-tax-return-john-doe.pdf);
            // logging them here would leak that PII to the application log (which is
            // typically retained outside the encrypted DB tier and read by operators
            // who may have log-read but not document-read access).
            log.warn("Document {}: redaction failed via {}: {}",
                    doc.getId(), e.getClass().getSimpleName(), e.getMessage(), e);
            markFailed(doc, e.getMessage(), e);
        }
    }

    private void markFailed(final Document doc, final String message, final Throwable cause) {
        doc.changeStatus("FAILED");
        doc.setFailureMessage(buildFailureMessage(message, cause));
        try {
            documentRepository.save(doc);
        } catch (Exception persistFailure) {
            log.warn("Document {}: also failed to persist FAILED status: {}",
                    doc.getId(), persistFailure.getMessage());
        }
    }

    /** Compose a multi-line failure message: short summary + class + a trimmed stack. */
    private static String buildFailureMessage(final String message, final Throwable cause) {
        final StringBuilder sb = new StringBuilder();
        sb.append(message == null || message.isBlank() ? "(no message)" : message);
        if (cause != null) {
            sb.append("\n\n").append(cause.getClass().getName());
            int frames = 0;
            for (StackTraceElement frame : cause.getStackTrace()) {
                sb.append("\n    at ").append(frame.toString());
                if (++frames >= 12) break;
            }
            Throwable c = cause.getCause();
            int depth = 0;
            while (c != null && depth < 3) {
                sb.append("\nCaused by: ").append(c.getClass().getName())
                        .append(": ").append(c.getMessage());
                c = c.getCause();
                depth++;
            }
        }
        final String full = sb.toString();
        // Trim very large traces so we don't blow up the document row.
        return full.length() <= 8000 ? full : full.substring(0, 8000) + "\n…(truncated)";
    }

    private RedactionResponse runRedaction(final Document doc, final Batch batch) throws java.io.IOException {
        final PendingUpload pu = pendingUploadRepository.findById(doc.getId()).orElse(null);
        if (pu != null && MediaType.APPLICATION_PDF_VALUE.equals(pu.getContentType())) {
            return redactionService.redactPdf(new ByteArrayInputStream(pu.getContent()),
                    batch.getPhilterInstanceId(), batch.getContext());
        }
        final String text;
        if (pu != null) {
            text = new String(pu.getContent(), StandardCharsets.UTF_8);
        } else {
            text = doc.getOriginalText() == null ? "" : doc.getOriginalText();
        }
        return redactionService.redactText(text, batch.getPhilterInstanceId(), batch.getContext());
    }
}
