/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.health;

import ai.philterd.arbiter.service.OpenSearchIndexService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenSearchHealthIndicatorTest {

    private static Status statusFor(final OpenSearchIndexService.ClusterState state) {
        final OpenSearchIndexService service = mock(OpenSearchIndexService.class);
        when(service.probeCluster()).thenReturn(state);
        return new OpenSearchHealthIndicator(service).health().getStatus();
    }

    @Test
    void reachableClusterIsUp() {
        assertEquals(Status.UP, statusFor(OpenSearchIndexService.ClusterState.REACHABLE));
    }

    /** The fault the appliance console could not see before: search is configured but broken. */
    @Test
    void unreachableClusterIsDown() {
        assertEquals(Status.DOWN, statusFor(OpenSearchIndexService.ClusterState.UNREACHABLE));
    }

    /** UNKNOWN ranks below UP, so a deployment without OpenSearch still aggregates to UP. */
    @Test
    void disabledFullTextSearchIsUnknownRatherThanDown() {
        assertEquals(Status.UNKNOWN, statusFor(OpenSearchIndexService.ClusterState.DISABLED));
    }

    /** The probe reads settings from MongoDB, so it can block. Health must answer anyway. */
    @Test
    void aProbeThatOverrunsItsBudgetIsDown() {
        final OpenSearchIndexService service = mock(OpenSearchIndexService.class);
        when(service.probeCluster()).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            return OpenSearchIndexService.ClusterState.REACHABLE;
        });

        final long start = System.nanoTime();
        final Status status = new OpenSearchHealthIndicator(service, Duration.ofMillis(100)).health().getStatus();
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(Status.DOWN, status);
        assertTrue(elapsedMs < 5_000, "health should return at the budget, took " + elapsedMs + "ms");
    }

}
