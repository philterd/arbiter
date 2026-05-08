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

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Filesystem destination that finalized documents may be written to.
 */
@Document(collection = "local_directory_destinations")
public class LocalDirectoryDestination {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    /** Absolute path on the application server's filesystem. */
    private String directoryPath;

    private LocalDateTime createdAt;

    public LocalDirectoryDestination() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(final String name) { this.name = name; }

    public String getDirectoryPath() { return directoryPath; }
    public void setDirectoryPath(final String directoryPath) { this.directoryPath = directoryPath; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }
}
