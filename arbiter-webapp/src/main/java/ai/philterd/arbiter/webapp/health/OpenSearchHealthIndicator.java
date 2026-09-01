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
import ai.philterd.arbiter.service.OpenSearchIndexService.ClusterState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Adds the full-text search cluster to {@code /actuator/health} as {@code openSearch}. MongoDB
 * and Valkey get auto-configured indicators; OpenSearch is plain HTTP to a runtime-configured
 * endpoint, so it needs this. Details name a state only, never the endpoint URL.
 */
@Component("openSearch")
public class OpenSearchHealthIndicator implements HealthIndicator {

    /** Ceiling on the probe: it reads settings from MongoDB, so a MongoDB outage blocks it. */
    private static final Duration DEFAULT_BUDGET = Duration.ofSeconds(5);

    /** One virtual thread per probe, so a slow probe never holds the caller. */
    private static final Executor PROBE_EXECUTOR = task -> Thread.ofVirtual().start(task);

    private final OpenSearchIndexService openSearchIndexService;

    private final Duration budget;

    @Autowired
    public OpenSearchHealthIndicator(final OpenSearchIndexService openSearchIndexService) {
        this(openSearchIndexService, DEFAULT_BUDGET);
    }

    /** Test seam for a short budget. */
    OpenSearchHealthIndicator(final OpenSearchIndexService openSearchIndexService, final Duration budget) {
        this.openSearchIndexService = openSearchIndexService;
        this.budget = budget;
    }

    @Override
    public Health health() {
        return switch (probeWithinBudget()) {
            case REACHABLE -> Health.up().withDetail("fullTextSearch", "enabled").build();
            // UNKNOWN, not UP: nothing was checked. It ranks below UP, so a deployment
            // without full-text search still aggregates to UP.
            case DISABLED -> Health.unknown().withDetail("fullTextSearch", "disabled").build();
            case UNREACHABLE -> Health.down().withDetail("fullTextSearch", "enabled, cluster unreachable").build();
        };
    }

    private ClusterState probeWithinBudget() {
        final CompletableFuture<ClusterState> probe =
                CompletableFuture.supplyAsync(openSearchIndexService::probeCluster, PROBE_EXECUTOR);
        try {
            return probe.get(budget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ClusterState.UNREACHABLE;
        } catch (TimeoutException | java.util.concurrent.ExecutionException e) {
            // Not finishing in budget is not evidence of a usable cluster.
            probe.cancel(true);
            return ClusterState.UNREACHABLE;
        }
    }

}
