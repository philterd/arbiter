/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.dto;

/**
 * Patch payload for a span. {@code reason} is required only when the change overturns
 * a prior approval set by a different reviewer (status moving out of APPROVED); it is
 * recorded on the audit log entry for that change.
 */
public record SpanUpdateRequest(String status, String type, String reason, String exemptionCode) {
    public SpanUpdateRequest(final String status, final String type) {
        this(status, type, null, null);
    }
    public SpanUpdateRequest(final String status, final String type, final String reason) {
        this(status, type, reason, null);
    }
}
