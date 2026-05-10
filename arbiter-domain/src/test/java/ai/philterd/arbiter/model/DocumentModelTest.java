/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Document} state-bearing helpers — most accessors are trivial and
 * not worth dedicated tests, but {@code changeStatus()}, the lock predicate
 * {@link Document#isLocked(Instant)}, and the {@code approvedBy} list contract are
 * non-trivial enough that a regression would silently change reviewer-facing behavior.
 */
class DocumentModelTest {

    @Test
    void changeStatusSetsBothStatusAndChangeTimestamp() {
        final Document d = new Document();
        assertNull(d.getStatus());
        assertNull(d.getStatusChangedAt());

        final LocalDateTime before = LocalDateTime.now();
        d.changeStatus("APPROVED");
        final LocalDateTime after = LocalDateTime.now();

        assertEquals("APPROVED", d.getStatus());
        assertNotNull(d.getStatusChangedAt(),
                "changeStatus must set the change timestamp atomically with the status.");
        assertFalse(d.getStatusChangedAt().isBefore(before),
                "Timestamp must be at or after the call's start instant.");
        assertFalse(d.getStatusChangedAt().isAfter(after.plusSeconds(1)),
                "Timestamp must be at or before the call's end instant (with slack).");
    }

    @Test
    void changeStatusRefreshesTimestampOnSubsequentCalls() {
        final Document d = new Document();
        d.changeStatus("REVIEW_REQUIRED");
        final LocalDateTime first = d.getStatusChangedAt();

        // Force a small gap so we can detect the refresh deterministically.
        try { Thread.sleep(10); } catch (InterruptedException ignored) { /* fine */ }

        d.changeStatus("APPROVED");
        assertEquals("APPROVED", d.getStatus());
        assertTrue(d.getStatusChangedAt().isAfter(first),
                "Each changeStatus call must refresh the timestamp; otherwise the audit log's "
                        + "view of when the doc transitioned would be stuck on the first event.");
    }

    @Test
    void changeStatusAcceptsNullAndClearsBothFieldsConsistently() {
        // The model doesn't reject null, and there's no defensive guard. Verify the
        // observable contract: passing null leaves status null and still refreshes the
        // timestamp (so an explicit clear is still recorded).
        final Document d = new Document();
        d.changeStatus("APPROVED");
        d.changeStatus(null);
        assertNull(d.getStatus());
        assertNotNull(d.getStatusChangedAt());
    }

    // ---------- approvedBy list contract ----------

    @Test
    void getApprovedByLazilyInitialisesNullList() {
        // Documents loaded from Mongo with a missing field have a null list. The getter
        // must materialize an empty mutable list so callers can {@code .add()} without
        // a separate null guard.
        final Document d = new Document();
        d.setApprovedBy(null);
        assertNotNull(d.getApprovedBy(),
                "getter must replace a null backing list with an empty mutable list.");
        d.getApprovedBy().add("alice@x.com");
        assertEquals(1, d.getApprovedBy().size());
    }

    @Test
    void setApprovedByNullProducesEmptyMutableList() {
        // Symmetric defense in the setter.
        final Document d = new Document();
        d.setApprovedBy(null);
        // Mutable: must accept add() without throwing.
        d.getApprovedBy().add("bob@x.com");
        assertEquals(1, d.getApprovedBy().size());
    }

    // ---------- lock predicate ----------

    @Test
    void documentWithoutLockIsNotLocked() {
        final Document d = new Document();
        assertFalse(d.isLocked(Instant.now()),
                "A document with no lock fields set must report not-locked.");
    }

    @Test
    void documentWithExpiredLockIsNotLocked() {
        // The lock has a sliding expiry — once {@code lockExpiresAt} is in the past, the
        // lock is dead from the queue's perspective even though the metadata remains on
        // the document until the next acquisition cycle clears it.
        final Document d = new Document();
        d.setLockedBy("alice@x.com");
        d.setLockedAt(Instant.parse("2026-05-09T00:00:00Z"));
        d.setLockExpiresAt(Instant.parse("2026-05-09T00:05:00Z"));

        assertFalse(d.isLocked(Instant.parse("2026-05-09T00:05:00Z")),
                "isLocked uses strict isAfter — exactly at expiry counts as expired.");
        assertFalse(d.isLocked(Instant.parse("2026-05-09T01:00:00Z")));
    }

    @Test
    void documentWithFutureExpiryIsLocked() {
        final Document d = new Document();
        d.setLockedBy("alice@x.com");
        d.setLockedAt(Instant.parse("2026-05-09T00:00:00Z"));
        d.setLockExpiresAt(Instant.parse("2026-05-09T00:05:00Z"));

        assertTrue(d.isLocked(Instant.parse("2026-05-09T00:04:59Z")),
                "Until the moment of expiry, the lock is live.");
    }

    @Test
    void documentWithExpirySetButNoHolderIsNotLocked() {
        // Defensive: a half-written lock (expiry present, holder cleared) must report
        // not-locked. Otherwise a stale expiry could keep the document permanently
        // unreviewable.
        final Document d = new Document();
        d.setLockedBy(null);
        d.setLockExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        assertFalse(d.isLocked(Instant.now()),
                "A null lockedBy must override a future expiry.");
    }

    @Test
    void documentWithHolderButNoExpiryIsNotLocked() {
        // Symmetrical defense: a holder without an expiry could otherwise be treated as
        // a permanent lock. The implementation requires both fields to be set.
        final Document d = new Document();
        d.setLockedBy("alice@x.com");
        d.setLockExpiresAt(null);
        assertFalse(d.isLocked(Instant.now()));
    }

    // ---------- priority defaults ----------

    @Test
    void priorityDefaultsToTwoForNewDocument() {
        // 2 = Normal; the default keeps untagged documents from accidentally being
        // treated as low priority (which would push them to the bottom of the priority
        // sort) or high priority (which would jump the queue).
        assertEquals(2, new Document().getPriority());
    }

    @Test
    void priorityIsRetainedThroughASetterRoundTrip() {
        final Document d = new Document();
        d.setPriority(3);
        assertEquals(3, d.getPriority());
        d.setPriority(1);
        assertEquals(1, d.getPriority());
    }
}
