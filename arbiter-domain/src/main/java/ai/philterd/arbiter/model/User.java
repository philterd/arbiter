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

import java.util.HashSet;
import java.util.Set;

@Document(collection = "users")
public class User {

    @Id
    private String id;

    @Indexed(unique = true)
    private String email;

    private String passwordHash;

    @Indexed(unique = true, sparse = true)
    private String apiKey;

    private Set<String> roles = new HashSet<>();

    private LocalDateTime createdAt;

    /** Total documents this user has approved or rejected. Used by approval rules. */
    private long reviewCount;

    private boolean mfaEnabled;
    private String totpSecret;

    /**
     * When true, the user must change their password on next login before they
     * can reach any other page. Set when an admin creates an account with an
     * initial password, or resets an existing user's password — the admin knows
     * the password and the user must rotate it before doing anything else.
     * Cleared when the user successfully changes their password from Settings.
     */
    private boolean mustChangePassword;

    public User() {
    }

    public long getReviewCount() { return reviewCount; }
    public void setReviewCount(final long reviewCount) { this.reviewCount = reviewCount; }

    public boolean isMfaEnabled() { return mfaEnabled; }
    public void setMfaEnabled(final boolean mfaEnabled) { this.mfaEnabled = mfaEnabled; }

    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(final boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    public String getTotpSecret() { return totpSecret; }
    public void setTotpSecret(final String totpSecret) { this.totpSecret = totpSecret; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }


    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(final String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(final String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(final String apiKey) {
        this.apiKey = apiKey;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public void setRoles(final Set<String> roles) {
        this.roles = roles;
    }
}
