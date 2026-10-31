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
import ai.philterd.workspace.service.DocumentSearchService.State;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Adds the full-text search backend to {@code /actuator/health} as {@code fullTextSearch}: the embedded index
 * opening, or the OpenSearch cluster answering. Details name a state only, never the endpoint URL or index path.
 */
@Component("fullTextSearch")
public class FullTextSearchHealthIndicator implements HealthIndicator {

    /** Ceiling on the probe: it reads settings from MongoDB, so a MongoDB outage blocks it. */
    private static final Duration DEFAULT_BUDGET = Duration.ofSeconds(5);

    /** One virtual thread per probe, so a slow probe never holds the caller. */
    private static final Executor PROBE_EXECUTOR = task -> Thread.ofVirtual().start(task);

    private final DocumentSearchService searchService;

    private final Duration budget;

    @Autowired
    public FullTextSearchHealthIndicator(final DocumentSearchService searchService) {
        this(searchService, DEFAULT_BUDGET);
    }

    /** Test seam for a short budget. */
    FullTextSearchHealthIndicator(final DocumentSearchService searchService, final Duration budget) {
        this.searchService = searchService;
        this.budget = budget;
    }

    @Override
    public Health health() {
        return switch (probeWithinBudget()) {
            case REACHABLE -> Health.up().withDetail("fullTextSearch", "enabled").build();
            // UNKNOWN, not UP: nothing was checked. It ranks below UP, so a deployment
            // without full-text search still aggregates to UP.
            case DISABLED -> Health.unknown().withDetail("fullTextSearch", "disabled").build();
            case UNREACHABLE -> Health.down().withDetail("fullTextSearch", "enabled, backend unreachable").build();
        };
    }

    private State probeWithinBudget() {
        final CompletableFuture<State> probe =
                CompletableFuture.supplyAsync(searchService::probe, PROBE_EXECUTOR);
        try {
            return probe.get(budget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return State.UNREACHABLE;
        } catch (TimeoutException | java.util.concurrent.ExecutionException e) {
            // Not finishing in budget is not evidence of a usable cluster.
            probe.cancel(true);
            return State.UNREACHABLE;
        }
    }

}
