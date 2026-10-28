/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.store;

import ai.philterd.workspace.model.BackgroundJob;

import java.util.Collection;

/**
 * Atomic operations on background jobs that the job dispatchers rely on.
 */
public interface BackgroundJobStore {

    /** Number of RUNNING jobs of the given types, across all replicas. */
    long countRunning(Collection<String> types);

    /**
     * Atomically flip a job from PENDING to RUNNING and stamp {@code startedAt}. Returns the
     * updated job, or {@code null} when another worker already claimed it or a uniqueness rule
     * (one RUNNING data import per batch) refuses the transition.
     */
    BackgroundJob claimPending(String jobId);
}
