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
 * Amazon SQS destination that finalized documents may be enqueued to. The
 * queue URL encodes the region, so it's the only required AWS-side identifier.
 * Credentials are encrypted at rest with the application's symmetric key
 * (see {@code SymmetricCipher}); when null/empty the application's ambient AWS
 * credentials are used.
 */
@Document(collection = "sqs_destinations")
public class SqsDestination {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    /** Full SQS queue URL, e.g. https://sqs.us-east-1.amazonaws.com/123456789012/MyQueue */
    private String queueUrl;

    private String encryptedAccessKey;

    private String encryptedSecretKey;

    private LocalDateTime createdAt;

    public SqsDestination() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(final String name) { this.name = name; }

    public String getQueueUrl() { return queueUrl; }
    public void setQueueUrl(final String queueUrl) { this.queueUrl = queueUrl; }

    public String getEncryptedAccessKey() { return encryptedAccessKey; }
    public void setEncryptedAccessKey(final String encryptedAccessKey) { this.encryptedAccessKey = encryptedAccessKey; }

    public String getEncryptedSecretKey() { return encryptedSecretKey; }
    public void setEncryptedSecretKey(final String encryptedSecretKey) { this.encryptedSecretKey = encryptedSecretKey; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }
}
