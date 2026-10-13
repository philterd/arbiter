/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.store.sqlite;

import ai.philterd.arbiter.model.AuditLog;
import ai.philterd.arbiter.model.BackgroundJob;
import ai.philterd.arbiter.model.Document;
import ai.philterd.arbiter.service.AuditLogQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SqliteStoresTest {

    @TempDir
    Path dir;

    private SqliteTestStore db;

    @BeforeEach
    void open() throws Exception {
        db = new SqliteTestStore(dir);
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    @Test
    void documentLockIsExclusiveUntilReleasedOrExpired() {
        final SqliteDocumentLockService locks = new SqliteDocumentLockService(db.store);
        final Document d = db.store.save(document("PENDING"));
        d.setOriginalText("secret text");
        db.store.save(d);

        final Document held = locks.acquire(d.getId(), "a@x.com");
        assertEquals("a@x.com", held.getLockedBy());
        assertEquals("secret text", held.getOriginalText());
        assertNull(locks.acquire(d.getId(), "b@x.com"));
        assertNotNull(locks.acquire(d.getId(), "a@x.com"));
        assertNotNull(locks.pulse(d.getId(), "a@x.com"));
        assertNull(locks.pulse(d.getId(), "b@x.com"));

        locks.release(d.getId(), "b@x.com");
        assertNull(locks.acquire(d.getId(), "b@x.com"));
        locks.release(d.getId(), "a@x.com");
        assertEquals("b@x.com", locks.acquire(d.getId(), "b@x.com").getLockedBy());

        // An expired lock can be taken over.
        db.store.updateOne(Document.class, Where.eq("id", d.getId()),
                java.util.Map.of("lockExpiresAt", Instant.now().minusSeconds(1)), List.of());
        assertEquals("c@x.com", locks.acquire(d.getId(), "c@x.com").getLockedBy());

        final Document broken = locks.breakLock(d.getId());
        assertNull(broken.getLockedBy());
        assertNull(broken.getLockExpiresAt());
        // Lock changes leave the optimistic-lock version alone, as findAndModify does.
        assertEquals(1L, db.store.findById(Document.class, d.getId()).orElseThrow().getVersion());
    }

    @Test
    void onlyOneOfManyConcurrentWorkersClaimsADocument() throws Exception {
        final SqliteDocumentQueueStore queue = new SqliteDocumentQueueStore(db.store);
        final Document d = db.store.save(document("PENDING"));
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final List<Callable<Document>> claims = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                claims.add(() -> queue.claimPending(d.getId()));
            }
            int won = 0;
            for (Future<Document> f : pool.invokeAll(claims)) {
                if (f.get() != null) {
                    won++;
                    assertEquals("PROCESSING", f.get().getStatus());
                }
            }
            assertEquals(1, won);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void queueRequeuesStuckClaimsAndCountsWindows() {
        final SqliteDocumentQueueStore queue = new SqliteDocumentQueueStore(db.store);
        final LocalDateTime now = LocalDateTime.now();
        final Document stuck = document("PROCESSING");
        stuck.setStatusChangedAt(now.minusHours(2));
        db.store.save(stuck);
        final Document fresh = document("PROCESSING");
        fresh.setStatusChangedAt(now);
        db.store.save(fresh);

        assertEquals(1, queue.requeueProcessingBefore(now.minusHours(1)));
        assertEquals("PENDING", db.store.findById(Document.class, stuck.getId()).orElseThrow().getStatus());
        assertEquals(2, queue.countCreatedBetween(now.minusDays(1), now.plusMinutes(1)));
        assertEquals(1, queue.countStatusChangedBetween(now.minusDays(1), now.plusMinutes(1), List.of("PENDING")));
    }

    @Test
    void queueRemovesOnlyDocumentsInTheGivenStatus() {
        final SqliteDocumentQueueStore queue = new SqliteDocumentQueueStore(db.store);
        final Document failed = db.store.save(document("FAILED"));
        final Document pending = db.store.save(document("PENDING"));

        assertNull(queue.removeIfStatus(pending.getId(), "FAILED"));
        assertEquals(failed.getId(), queue.removeIfStatus(failed.getId(), "FAILED").getId());
        db.store.save(document("FAILED"));
        db.store.save(document("FAILED"));
        assertEquals(2, queue.removeAllWithStatus("FAILED").size());
        assertEquals(1, db.store.count(Document.class, Where.ALL));
    }

    @Test
    void partialUniqueIndexAllowsOneRunningImportPerBatch() {
        final SqliteBackgroundJobStore jobs = new SqliteBackgroundJobStore(db.store);
        final BackgroundJob a = db.store.save(job("b1", BackgroundJob.TYPE_OPENSEARCH_INGEST));
        final BackgroundJob b = db.store.save(job("b1", BackgroundJob.TYPE_RDB_INGEST));
        final BackgroundJob other = db.store.save(job("b2", BackgroundJob.TYPE_OPENSEARCH_INGEST));
        final BackgroundJob export = db.store.save(job("b1", BackgroundJob.TYPE_BATCH_EXPORT));

        assertNotNull(jobs.claimPending(a.getId()));
        assertNull(jobs.claimPending(a.getId()));
        assertNull(jobs.claimPending(b.getId()));
        assertEquals("PENDING", db.store.findById(BackgroundJob.class, b.getId()).orElseThrow().getStatus());
        assertNotNull(jobs.claimPending(other.getId()));
        assertNotNull(jobs.claimPending(export.getId()));
        assertEquals(2, jobs.countRunning(List.of(BackgroundJob.TYPE_OPENSEARCH_INGEST, BackgroundJob.TYPE_RDB_INGEST)));
    }

    @Test
    void auditQueriesFilterSortAndPage() {
        final SqliteAuditLogQueryService audit = new SqliteAuditLogQueryService(db.store);
        final Instant t = Instant.parse("2026-05-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            db.store.save(entry(t.plusSeconds(i), "Document", "d1", i % 2 == 0 ? "a@x.com" : "b@x.com"));
        }
        db.store.save(entry(t.plusSeconds(10), "Span", "s1", "a@x.com"));
        db.store.save(entry(t.plusSeconds(11), "Span", "s9", "a@x.com"));

        final List<AuditLog> asc = audit.find(t.plusSeconds(1), t.plusSeconds(3), null, "Document", null, 0);
        assertEquals(List.of(t.plusSeconds(1), t.plusSeconds(2), t.plusSeconds(3)),
                asc.stream().map(AuditLog::getTimestamp).toList());

        final AuditLogQueryService.Result page = audit.query(
                new AuditLogQueryService.QueryFilters(null, null, "a@x.com", null, null, null, null, null), 0, 2);
        assertEquals(5, page.total());
        assertEquals(List.of(t.plusSeconds(11), t.plusSeconds(10)),
                page.entries().stream().map(AuditLog::getTimestamp).toList());

        assertEquals(6, audit.findForDocument("d1", List.of("s1")).size());
    }

    private static Document document(final String status) {
        final Document d = new Document();
        d.setStatus(status);
        d.setBatchId("b");
        d.setCreatedAt(LocalDateTime.now());
        return d;
    }

    private static BackgroundJob job(final String batchId, final String type) {
        final BackgroundJob j = new BackgroundJob();
        j.setBatchId(batchId);
        j.setType(type);
        j.setStatus(BackgroundJob.STATUS_PENDING);
        return j;
    }

    private static AuditLog entry(final Instant at, final String resourceType, final String resourceId, final String email) {
        final AuditLog a = new AuditLog();
        a.setTimestamp(at);
        a.setResourceType(resourceType);
        a.setResourceId(resourceId);
        a.setUserEmail(email);
        a.setAction("TEST");
        return a;
    }
}
