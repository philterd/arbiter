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

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Names a disposition rule applied to original documents when their batch's documents
 * are finalized. Each batch must reference one of these by id.
 */
@Document(collection = "finalization_policies")
public class FinalizationPolicy {

    /** Delete the original document {@code deleteAfterDays} days after finalize. */
    public static final String OPTION_DELETE_AFTER_X_DAYS = "DELETE_AFTER_X_DAYS";
    /** Delete immediately when finalize is recorded. */
    public static final String OPTION_DELETE_IMMEDIATELY = "DELETE_IMMEDIATELY";
    /** Delete 48 hours after finalize. */
    public static final String OPTION_DELETE_AFTER_48H = "DELETE_AFTER_48H";
    /** Retain indefinitely (legal hold). */
    public static final String OPTION_LEGAL_HOLD = "LEGAL_HOLD";

    /** Stable order, label per option — used to drive the admin form. */
    public static Map<String, String> labels() {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put(OPTION_DELETE_AFTER_X_DAYS, "Delete original documents after X days");
        m.put(OPTION_DELETE_IMMEDIATELY, "Delete original documents immediately after finalizing");
        m.put(OPTION_DELETE_AFTER_48H, "Delete original documents after 48 hours");
        m.put(OPTION_LEGAL_HOLD, "Leave original documents (legal hold)");
        return m;
    }

    public static boolean isValidOption(final String option) {
        return labels().containsKey(option);
    }

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    /** One of the OPTION_* constants. */
    private String option;

    /** Number of days for {@link #OPTION_DELETE_AFTER_X_DAYS}; ignored otherwise. */
    private long deleteAfterDays;

    private Instant createdAt;
    private Instant updatedAt;

    public FinalizationPolicy() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(final String name) { this.name = name; }

    public String getOption() { return option; }
    public void setOption(final String option) { this.option = option; }

    public long getDeleteAfterDays() { return deleteAfterDays; }
    public void setDeleteAfterDays(final long deleteAfterDays) { this.deleteAfterDays = deleteAfterDays; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(final Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(final Instant updatedAt) { this.updatedAt = updatedAt; }
}
