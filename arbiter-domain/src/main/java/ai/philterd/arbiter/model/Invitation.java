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
import java.util.HashSet;
import java.util.Set;

/**
 * One-shot invitation that lets a brand-new user choose their own password instead of
 * receiving one in plaintext over SMTP. The admin's create-user flow issues an invitation
 * and emails a link of the form {@code /invitations/{token}}. The token is held by the
 * recipient only — only its SHA-256 hash is stored here.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li><strong>Issued</strong> — admin creates the invitation, email goes out.</li>
 *   <li><strong>Redeemed</strong> — recipient sets a password; {@link #consumedAt} flips
 *       from {@code null} to the redemption timestamp; the {@code User} row is created.</li>
 *   <li><strong>Expired</strong> — past {@link #expiresAt}; redemption refused.</li>
 * </ul>
 *
 * <p>The pending email is stored as a unique index so a stale invitation can't sit alongside
 * a real {@code User} for the same address. {@code AdminController.create} clears the prior
 * pending row before issuing a new one.
 */
@Document(collection = "invitations")
public class Invitation {

    @Id
    private String id;

    /**
     * SHA-256 hash of the public token. Comparing the request-supplied token against this
     * (after a constant-time check) lets us validate without the plaintext token ever
     * touching the database.
     */
    private String tokenHash;

    @Indexed(unique = true, sparse = true)
    private String email;

    /** Whether the redeemed user becomes a {@link Roles#ADMIN}. */
    private boolean admin;

    private Set<String> groupIds = new HashSet<>();

    private Instant createdAt;

    /** Token is rejected past this instant. */
    private Instant expiresAt;

    /** Set when the invitation is redeemed. Null while pending. */
    private Instant consumedAt;

    public Invitation() {
    }

    public String getId() { return id; }
    public void setId(final String id) { this.id = id; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(final String tokenHash) { this.tokenHash = tokenHash; }

    public String getEmail() { return email; }
    public void setEmail(final String email) { this.email = email; }

    public boolean isAdmin() { return admin; }
    public void setAdmin(final boolean admin) { this.admin = admin; }

    public Set<String> getGroupIds() {
        if (groupIds == null) groupIds = new HashSet<>();
        return groupIds;
    }
    public void setGroupIds(final Set<String> groupIds) {
        this.groupIds = groupIds == null ? new HashSet<>() : groupIds;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(final Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(final Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(final Instant consumedAt) { this.consumedAt = consumedAt; }
}
