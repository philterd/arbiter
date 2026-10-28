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
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.HashSet;
import java.util.Set;

@Document(collection = "users")
public class User {

    @Id
    private String id;

    /**
     * Optimistic-lock counter (R2-F13). Spring Data Mongo increments this on
     * every save, and the save throws {@link org.springframework.dao.OptimisticLockingFailureException}
     * if the loaded version doesn't match what's in Mongo, so concurrent role
     * or group changes are surfaced rather than lost.
     */
    @Version
    private Long version;

    /**
     * The person's Philter username. People sign in through Philter, which keys users by username, so
     * this holds the username; the name is kept so existing rows, keyed by email before Philter
     * sign-in, still match a Philter user created with the email as its username.
     */
    @Indexed(unique = true)
    private String email;

    private Set<String> roles = new HashSet<>();

    private LocalDateTime createdAt;

    /** Total documents this user has approved or rejected. Used by approval rules. */
    private long reviewCount;

    public User() {
    }

    public long getReviewCount() { return reviewCount; }
    public void setReviewCount(final long reviewCount) { this.reviewCount = reviewCount; }

    public Long getVersion() { return version; }
    public void setVersion(final Long version) { this.version = version; }
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

    public Set<String> getRoles() {
        return roles;
    }

    public void setRoles(final Set<String> roles) {
        this.roles = roles;
    }
}
