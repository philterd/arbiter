/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.model.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentLockServiceTest {

    private MongoOperations mongoOperations;
    private DocumentLockService service;

    @BeforeEach
    void setUp() {
        mongoOperations = mock(MongoOperations.class);
        service = new DocumentLockService(mongoOperations);
    }

    private static Document docWithLock(final String holder) {
        final Document d = new Document();
        d.setId("doc-1");
        d.setLockedBy(holder);
        d.setLockedAt(Instant.now());
        d.setLockExpiresAt(Instant.now().plusSeconds(900));
        return d;
    }

    /**
     * Flatten a query/update Document into "/path" → value pairs we can search. We can't
     * use {@code toJson()} because the update carries {@link Instant} values that the
     * default BSON codec registry can't serialize.
     */
    private static java.util.Map<String, Object> flatten(final org.bson.Document doc) {
        final java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        flattenInto("", doc, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flattenInto(final String prefix, final Object node,
                                    final java.util.Map<String, Object> out) {
        if (node instanceof org.bson.Document d) {
            for (java.util.Map.Entry<String, Object> e : d.entrySet()) {
                flattenInto(prefix + "/" + e.getKey(), e.getValue(), out);
            }
        } else if (node instanceof java.util.List<?> list) {
            int i = 0;
            for (Object item : list) {
                flattenInto(prefix + "[" + (i++) + "]", item, out);
            }
        } else {
            out.put(prefix, node);
        }
    }

    private static java.util.Map<String, Object> queryFlat(final Query q) {
        return flatten(q.getQueryObject());
    }

    private static java.util.Map<String, Object> updateFlat(final Update u) {
        return flatten(u.getUpdateObject());
    }

    private static boolean hasPathContaining(final java.util.Map<String, Object> flat,
                                              final String pathFragment) {
        for (String p : flat.keySet()) {
            if (p.contains(pathFragment)) return true;
        }
        return false;
    }

    /** Find a flattened-path value by suffix match. Matches anywhere in the BSON tree. */
    private static Object findBySuffix(final java.util.Map<String, Object> flat,
                                        final String suffix) {
        for (java.util.Map.Entry<String, Object> e : flat.entrySet()) {
            if (e.getKey().endsWith(suffix)) return e.getValue();
        }
        return null;
    }

    // ---- acquire ----

    @Test
    void acquireReturnsNullForBlankEmail() {
        assertNull(service.acquire("doc-1", null));
        assertNull(service.acquire("doc-1", " "));
        verify(mongoOperations, never()).findAndModify(any(), any(), any(FindAndModifyOptions.class), any());
    }

    @Test
    void acquireReturnsNullForBlankDocumentId() {
        assertNull(service.acquire(null, "alice@example.com"));
        verify(mongoOperations, never()).findAndModify(any(), any(), any(FindAndModifyOptions.class), any());
    }

    @Test
    void acquireSucceedsWhenMongoMatchesCriteria() {
        final Document mongoResult = docWithLock("alice@example.com");
        when(mongoOperations.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Document.class))).thenReturn(mongoResult);

        final Document acquired = service.acquire("doc-1", "alice@example.com");

        assertNotNull(acquired);
        assertEquals("alice@example.com", acquired.getLockedBy());
    }

    @Test
    void acquireReturnsNullWhenLockHeldByOther() {
        // Mongo's findAndModify returns null when no document matches the criteria —
        // i.e. the lock is currently held by someone else and not yet expired.
        when(mongoOperations.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Document.class))).thenReturn(null);

        assertNull(service.acquire("doc-1", "alice@example.com"));
    }

    @Test
    void acquireBuildsQueryAllowingUnlockedExpiredOrSelfHeld() {
        when(mongoOperations.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Document.class)))
                .thenReturn(docWithLock("alice@example.com"));

        service.acquire("doc-1", "alice@example.com");

        final ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        final ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoOperations).findAndModify(queryCaptor.capture(), updateCaptor.capture(),
                any(FindAndModifyOptions.class), eq(Document.class));

        final java.util.Map<String, Object> q = queryFlat(queryCaptor.getValue());
        // The criteria should pin the document id and OR-allow four "claimable" cases.
        assertEquals("doc-1", findBySuffix(q, "/_id"), () -> "Query missing doc id: " + q);
        assertTrue(hasPathContaining(q, "$or"), () -> "Query missing OR clause: " + q);
        assertTrue(hasPathContaining(q, "lockedBy"), () -> "Query missing lockedBy clause: " + q);
        assertTrue(hasPathContaining(q, "lockExpiresAt"),
                () -> "Query missing lockExpiresAt clause: " + q);
        assertTrue(hasPathContaining(q, "$lte"), () -> "Query missing expiry comparison: " + q);

        final java.util.Map<String, Object> u = updateFlat(updateCaptor.getValue());
        assertEquals("alice@example.com", u.get("/$set/lockedBy"),
                () -> "Update should set lockedBy=email: " + u);
        assertTrue(u.containsKey("/$set/lockExpiresAt"),
                () -> "Update should set lockExpiresAt: " + u);
        assertTrue(u.containsKey("/$set/lockedAt"),
                () -> "Update should set lockedAt: " + u);
    }

    @Test
    void acquireUsesReturnNewSoCallerSeesPostUpdateState() {
        when(mongoOperations.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Document.class)))
                .thenReturn(docWithLock("alice@example.com"));

        service.acquire("doc-1", "alice@example.com");

        final ArgumentCaptor<FindAndModifyOptions> optsCaptor =
                ArgumentCaptor.forClass(FindAndModifyOptions.class);
        verify(mongoOperations).findAndModify(any(Query.class), any(Update.class),
                optsCaptor.capture(), eq(Document.class));
        assertTrue(optsCaptor.getValue().isReturnNew());
    }

    // ---- pulse ----

    @Test
    void pulseReturnsNullWhenLockNotHeldByCaller() {
        when(mongoOperations.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Document.class))).thenReturn(null);

        assertNull(service.pulse("doc-1", "bob@example.com"));
    }

    @Test
    void pulseSucceedsWhenLockHeldByCaller() {
        when(mongoOperations.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Document.class)))
                .thenReturn(docWithLock("alice@example.com"));

        final Document pulsed = service.pulse("doc-1", "alice@example.com");

        assertNotNull(pulsed);
        assertEquals("alice@example.com", pulsed.getLockedBy());
    }

    @Test
    void pulseRequiresExistingLockedByMatchAndOnlyTouchesExpiry() {
        when(mongoOperations.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Document.class)))
                .thenReturn(docWithLock("alice@example.com"));

        service.pulse("doc-1", "alice@example.com");

        final ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        final ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoOperations).findAndModify(queryCaptor.capture(), updateCaptor.capture(),
                any(FindAndModifyOptions.class), eq(Document.class));

        final java.util.Map<String, Object> q = queryFlat(queryCaptor.getValue());
        assertEquals("doc-1", findBySuffix(q, "/_id"), () -> "Query missing doc id: " + q);
        assertEquals("alice@example.com", findBySuffix(q, "/lockedBy"),
                () -> "Pulse must require existing lock by caller: " + q);

        final java.util.Map<String, Object> u = updateFlat(updateCaptor.getValue());
        // Pulse only updates lockExpiresAt — it must not overwrite lockedBy or lockedAt.
        assertTrue(u.containsKey("/$set/lockExpiresAt"),
                () -> "Pulse should set lockExpiresAt: " + u);
        assertFalse(u.containsKey("/$set/lockedBy"),
                () -> "Pulse must not write lockedBy: " + u);
        assertFalse(u.containsKey("/$set/lockedAt"),
                () -> "Pulse must not write lockedAt: " + u);
    }

    @Test
    void pulseReturnsNullForBlankInputs() {
        assertNull(service.pulse(null, "alice@example.com"));
        assertNull(service.pulse("doc-1", null));
        assertNull(service.pulse("doc-1", " "));
        verify(mongoOperations, never()).findAndModify(any(), any(), any(FindAndModifyOptions.class), any());
    }

    // ---- release ----

    @Test
    void releaseUnsetsAllLockFieldsOnlyWhenHeldByCaller() {
        service.release("doc-1", "alice@example.com");

        final ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        final ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoOperations).findAndModify(queryCaptor.capture(), updateCaptor.capture(),
                any(FindAndModifyOptions.class), eq(Document.class));

        final java.util.Map<String, Object> q = queryFlat(queryCaptor.getValue());
        assertEquals("doc-1", findBySuffix(q, "/_id"), () -> "Release query missing doc id: " + q);
        assertEquals("alice@example.com", findBySuffix(q, "/lockedBy"),
                () -> "Release must require holder match: " + q);

        final java.util.Map<String, Object> u = updateFlat(updateCaptor.getValue());
        assertTrue(u.containsKey("/$unset/lockedBy"),
                () -> "Release should clear lockedBy: " + u);
        assertTrue(u.containsKey("/$unset/lockedAt"),
                () -> "Release should clear lockedAt: " + u);
        assertTrue(u.containsKey("/$unset/lockExpiresAt"),
                () -> "Release should clear lockExpiresAt: " + u);
    }

    @Test
    void releaseIsNoOpForBlankInputs() {
        service.release(null, "alice@example.com");
        service.release("doc-1", null);
        service.release("doc-1", " ");
        verify(mongoOperations, never()).findAndModify(any(), any(), any(FindAndModifyOptions.class), any());
    }

    // ---- breakLock ----

    @Test
    void breakLockUnsetsAllFieldsRegardlessOfHolder() {
        when(mongoOperations.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Document.class)))
                .thenReturn(new Document());

        final Document result = service.breakLock("doc-1");
        assertNotNull(result);

        final ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        final ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoOperations).findAndModify(queryCaptor.capture(), updateCaptor.capture(),
                any(FindAndModifyOptions.class), eq(Document.class));

        final java.util.Map<String, Object> q = queryFlat(queryCaptor.getValue());
        assertEquals("doc-1", findBySuffix(q, "/_id"),
                () -> "Break-lock query missing doc id: " + q);
        // Break-lock must NOT require a lockedBy match — admins can clear regardless of holder.
        assertFalse(hasPathContaining(q, "lockedBy"),
                () -> "Break-lock must not depend on the current holder: " + q);

        final java.util.Map<String, Object> u = updateFlat(updateCaptor.getValue());
        assertTrue(u.containsKey("/$unset/lockedBy"));
        assertTrue(u.containsKey("/$unset/lockedAt"));
        assertTrue(u.containsKey("/$unset/lockExpiresAt"));
    }

    @Test
    void breakLockReturnsNullForBlankDocumentId() {
        assertNull(service.breakLock(null));
        verify(mongoOperations, never()).findAndModify(any(), any(), any(FindAndModifyOptions.class), any());
    }

    // ---- Document.isLocked helper ----

    @Test
    void isLockedReturnsTrueOnlyForUnexpiredHeldLock() {
        final Instant now = Instant.now();
        final Document held = docWithLock("alice@example.com");
        assertTrue(held.isLocked(now));

        final Document expired = docWithLock("alice@example.com");
        expired.setLockExpiresAt(now.minusSeconds(60));
        assertFalse(expired.isLocked(now));

        final Document fresh = new Document();
        fresh.setId("doc-1");
        // No lock fields set
        assertFalse(fresh.isLocked(now));

        final Document partial = new Document();
        partial.setId("doc-1");
        partial.setLockExpiresAt(now.plusSeconds(60));
        // Missing lockedBy → not locked.
        assertFalse(partial.isLocked(now));
    }

    // ---- exact-call wiring sanity check ----

    @Test
    void acquireMakesExactlyOneCallToFindAndModify() {
        when(mongoOperations.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Document.class)))
                .thenReturn(docWithLock("alice@example.com"));

        service.acquire("doc-1", "alice@example.com");

        verify(mongoOperations, times(1)).findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Document.class));
    }
}
