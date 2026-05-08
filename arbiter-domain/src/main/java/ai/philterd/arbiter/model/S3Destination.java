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
 * S3 destination that finalized documents may be written to. Credentials are encrypted
 * at rest with the application's symmetric key (see {@code SymmetricCipher}).
 */
@Document(collection = "s3_destinations")
public class S3Destination {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    private String bucketName;

    /** Object-key prefix under which finalized documents will be written. */
    private String bucketKey;

    /**
     * AES-GCM ciphertext of the AWS access key id. Null/empty means use the application's
     * ambient AWS credentials.
     */
    private String encryptedAccessKey;

    /** AES-GCM ciphertext of the AWS secret access key. */
    private String encryptedSecretKey;

    private LocalDateTime createdAt;

    public S3Destination() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(final String name) { this.name = name; }

    public String getBucketName() { return bucketName; }
    public void setBucketName(final String bucketName) { this.bucketName = bucketName; }

    public String getBucketKey() { return bucketKey; }
    public void setBucketKey(final String bucketKey) { this.bucketKey = bucketKey; }

    public String getEncryptedAccessKey() { return encryptedAccessKey; }
    public void setEncryptedAccessKey(final String encryptedAccessKey) { this.encryptedAccessKey = encryptedAccessKey; }

    public String getEncryptedSecretKey() { return encryptedSecretKey; }
    public void setEncryptedSecretKey(final String encryptedSecretKey) { this.encryptedSecretKey = encryptedSecretKey; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }
}
