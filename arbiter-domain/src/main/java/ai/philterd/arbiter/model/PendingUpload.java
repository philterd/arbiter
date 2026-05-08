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

import java.time.LocalDateTime;

/**
 * Sidecar record holding the raw bytes for a queued upload that the redaction worker still needs
 * to process. Keyed by document id so the worker can fetch the bytes for a {@code PENDING}
 * {@link ai.philterd.arbiter.model.Document}, run redaction, then delete the sidecar entry.
 */
@Document(collection = "pending_uploads")
public class PendingUpload {

    @Id
    private String id;

    private String contentType;

    private byte[] content;

    private LocalDateTime createdAt;

    public PendingUpload() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getContentType() { return contentType; }
    public void setContentType(final String contentType) { this.contentType = contentType; }

    public byte[] getContent() { return content; }
    public void setContent(final byte[] content) { this.content = content; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }
}
