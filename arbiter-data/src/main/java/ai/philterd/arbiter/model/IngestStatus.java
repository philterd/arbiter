/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.model;

import java.util.concurrent.ThreadLocalRandom;

public final class IngestStatus {

    private IngestStatus() {
    }

    public static String pick(final Batch batch, final boolean needsReview) {
        if (needsReview) return "REVIEW_REQUIRED";
        final double rate = batch == null ? 0.0 : batch.getAuditSamplingRate();
        if (rate <= 0.0) return "AUTO_APPROVED";
        if (rate >= 1.0) return "AUDIT_REQUIRED";
        return ThreadLocalRandom.current().nextDouble() < rate ? "AUDIT_REQUIRED" : "AUTO_APPROVED";
    }
}
