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

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpanModelTest {

    @Test
    void changeStatusUpdatesStatusAndTimestampLeavingActorUntouched() {
        // The no-actor overload is used for system transitions (auto-approve at ingest).
        // It must NOT invent a {@code statusChangedBy} value — leaving the field null
        // is how downstream code recognises a system action vs a reviewer action.
        final Span s = new Span();
        s.changeStatus("APPROVED");

        assertEquals("APPROVED", s.getStatus());
        assertNotNull(s.getStatusChangedAt());
        assertNull(s.getStatusChangedBy(),
                "System changeStatus must not set an actor — null is the system signal.");
    }

    @Test
    void changeStatusWithActorStampsAllThreeFields() {
        final Span s = new Span();
        s.changeStatus("REJECTED", "alice@x.com");

        assertEquals("REJECTED", s.getStatus());
        assertEquals("alice@x.com", s.getStatusChangedBy());
        assertNotNull(s.getStatusChangedAt());
    }

    @Test
    void changeStatusWithActorPreservesPreviousActorWhenCalledWithoutOne() {
        // The system overload only refreshes status + timestamp. A previously-stored
        // actor must remain — otherwise a system transition (e.g. ingest re-running)
        // would erase the human attribution.
        final Span s = new Span();
        s.changeStatus("APPROVED", "alice@x.com");
        s.changeStatus("REVIEW_REQUIRED");
        assertEquals("alice@x.com", s.getStatusChangedBy(),
                "System overload of changeStatus must not erase the prior reviewer attribution.");
    }

    @Test
    void changeStatusWithActorOverwritesPreviousActor() {
        final Span s = new Span();
        s.changeStatus("APPROVED", "alice@x.com");
        s.changeStatus("REJECTED", "bob@x.com");
        assertEquals("bob@x.com", s.getStatusChangedBy(),
                "When a new actor is supplied, it must replace the prior one — that's how a "
                        + "second reviewer overturning a decision is recorded.");
    }

    @Test
    void timestampAdvancesAcrossConsecutiveChangeStatusCalls() {
        final Span s = new Span();
        s.changeStatus("APPROVED");
        final LocalDateTime first = s.getStatusChangedAt();
        try { Thread.sleep(10); } catch (InterruptedException ignored) { /* fine */ }
        s.changeStatus("REJECTED");

        assertTrue(s.getStatusChangedAt().isAfter(first),
                "Each transition must produce a fresh timestamp so the audit trail can order events.");
    }

    @Test
    void needsSecondOpinionConstantMatchesPersistedString() {
        // The constant is referenced by string equality across the codebase
        // (controllers, exports, audit). A regression that renamed it without updating
        // every comparison would silently break the second-opinion workflow.
        assertEquals("NEEDS_SECOND_OPINION", Span.STATUS_NEEDS_SECOND_OPINION);
    }
}
