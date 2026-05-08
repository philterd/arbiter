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

import java.time.LocalDateTime;

@Document(collection = "inbox_messages")
public class InboxMessage {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String message;

    private LocalDateTime createdAt;

    private boolean read;

    private boolean archived;

    private LocalDateTime archivedAt;

    /**
     * When true, the message body is rendered as HTML by the inbox view. Reserved for
     * system-generated messages where the content is fully controlled by the application
     * (e.g. the welcome message); never set this from user-supplied input.
     */
    private boolean html;

    public InboxMessage() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(final String userId) { this.userId = userId; }

    public String getMessage() { return message; }
    public void setMessage(final String message) { this.message = message; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }

    public boolean isRead() { return read; }
    public void setRead(final boolean read) { this.read = read; }

    public boolean isArchived() { return archived; }
    public void setArchived(final boolean archived) { this.archived = archived; }

    public LocalDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(final LocalDateTime archivedAt) { this.archivedAt = archivedAt; }

    public boolean isHtml() { return html; }
    public void setHtml(final boolean html) { this.html = html; }
}
