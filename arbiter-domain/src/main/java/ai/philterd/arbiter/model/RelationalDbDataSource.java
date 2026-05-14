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

@Document(collection = "relational_db_data_sources")
public class RelationalDbDataSource {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    /**
     * AES-GCM ciphertext of the JDBC URL (see {@code SymmetricCipher}). The plaintext is a
     * standard JDBC URL, e.g. {@code jdbc:postgresql://host:5432/db}. Stored encrypted
     * because JDBC URLs can carry credentials in query parameters (e.g.
     * {@code …/db?user=alice&password=…}) and connection-string flags that some drivers
     * accept. {@code JdbcUrlValidator} refuses {@code user:pass@} userinfo and a known set
     * of dangerous query parameters at save time, but encrypting at rest is the
     * belt-and-braces guarantee: even a future validator regression can't surface a
     * cleartext credential out of the database.
     */
    private String encryptedJdbcUrl;

    /**
     * SQL query that returns the documents to be imported. The first column of each row is
     * expected to hold the document text.
     */
    private String sqlQuery;

    /**
     * AES-GCM ciphertext of the database username (see {@code SymmetricCipher}). Null/empty
     * means no explicit credential was configured — the runtime should fall back to whatever
     * authentication the JDBC URL itself supplies.
     */
    private String encryptedUsername;

    /** AES-GCM ciphertext of the database password. Null/empty when no password is set. */
    private String encryptedPassword;

    private LocalDateTime createdAt;

    public RelationalDbDataSource() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(final String name) { this.name = name; }

    public String getEncryptedJdbcUrl() { return encryptedJdbcUrl; }
    public void setEncryptedJdbcUrl(final String encryptedJdbcUrl) { this.encryptedJdbcUrl = encryptedJdbcUrl; }

    public String getSqlQuery() { return sqlQuery; }
    public void setSqlQuery(final String sqlQuery) { this.sqlQuery = sqlQuery; }

    public String getEncryptedUsername() { return encryptedUsername; }
    public void setEncryptedUsername(final String encryptedUsername) { this.encryptedUsername = encryptedUsername; }

    public String getEncryptedPassword() { return encryptedPassword; }
    public void setEncryptedPassword(final String encryptedPassword) { this.encryptedPassword = encryptedPassword; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }
}
