/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.health;

import ai.philterd.workspace.service.DocumentSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FullTextSearchHealthIndicatorTest {

    private static Status statusFor(final DocumentSearchService.State state) {
        final DocumentSearchService service = mock(DocumentSearchService.class);
        when(service.probe()).thenReturn(state);
        return new FullTextSearchHealthIndicator(service).health().getStatus();
    }

    @Test
    void reachableClusterIsUp() {
        assertEquals(Status.UP, statusFor(DocumentSearchService.State.REACHABLE));
    }

    /** The fault the appliance console could not see before: search is configured but broken. */
    @Test
    void unreachableClusterIsDown() {
        assertEquals(Status.DOWN, statusFor(DocumentSearchService.State.UNREACHABLE));
    }

    /** UNKNOWN ranks below UP, so a deployment without OpenSearch still aggregates to UP. */
    @Test
    void disabledFullTextSearchIsUnknownRatherThanDown() {
        assertEquals(Status.UNKNOWN, statusFor(DocumentSearchService.State.DISABLED));
    }

    /** The probe reads settings from MongoDB, so it can block. Health must answer anyway. */
    @Test
    void aProbeThatOverrunsItsBudgetIsDown() {
        final DocumentSearchService service = mock(DocumentSearchService.class);
        when(service.probe()).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            return DocumentSearchService.State.REACHABLE;
        });

        final long start = System.nanoTime();
        final Status status = new FullTextSearchHealthIndicator(service, Duration.ofMillis(100)).health().getStatus();
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(Status.DOWN, status);
        assertTrue(elapsedMs < 5_000, "health should return at the budget, took " + elapsedMs + "ms");
    }

}
