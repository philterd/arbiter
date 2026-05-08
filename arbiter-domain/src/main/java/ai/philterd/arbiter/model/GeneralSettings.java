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
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "settings")
public class GeneralSettings {

    public static final String SINGLETON_ID = "general";

    @Id
    private String id = SINGLETON_ID;

    private String arbiterUrl;
    private String timezone;
    private String opensearchEndpoint;
    /**
     * Maximum size of a single uploaded document, in bytes. Enforced by the upload and ingest
     * endpoints regardless of which path is used. {@code 0} means unset (the service default
     * applies on read).
     */
    private long maxUploadFileSizeBytes;
    private boolean requireMfa;
    /**
     * Maximum number of data-import jobs allowed to run concurrently across the whole
     * deployment. Bounded to 1–10 by the admin form; the {@link
     * ai.philterd.arbiter.service.GeneralSettingsService} default of {@code 1} is
     * applied at read time when the persisted value is unset or out of range.
     */
    private int maxConcurrentDataImports;

    public GeneralSettings() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getArbiterUrl() { return arbiterUrl; }
    public void setArbiterUrl(final String arbiterUrl) { this.arbiterUrl = arbiterUrl; }

    public String getTimezone() { return timezone; }
    public void setTimezone(final String timezone) { this.timezone = timezone; }

    public String getOpensearchEndpoint() { return opensearchEndpoint; }
    public void setOpensearchEndpoint(final String opensearchEndpoint) { this.opensearchEndpoint = opensearchEndpoint; }

    public long getMaxUploadFileSizeBytes() { return maxUploadFileSizeBytes; }
    public void setMaxUploadFileSizeBytes(final long maxUploadFileSizeBytes) {
        this.maxUploadFileSizeBytes = maxUploadFileSizeBytes;
    }

    public boolean isRequireMfa() { return requireMfa; }
    public void setRequireMfa(final boolean requireMfa) { this.requireMfa = requireMfa; }

    public int getMaxConcurrentDataImports() { return maxConcurrentDataImports; }
    public void setMaxConcurrentDataImports(final int maxConcurrentDataImports) {
        this.maxConcurrentDataImports = maxConcurrentDataImports;
    }
}
