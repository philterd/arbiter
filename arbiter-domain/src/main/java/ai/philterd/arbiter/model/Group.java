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

@Document(collection = "groups")
public class Group {

    @Id
    private String id;

    @Indexed(unique = true)
    private String name;

    private Set<String> userIds = new HashSet<>();

    /**
     * Subset of {@link #userIds} that lead this group. A team lead has admin-equivalent
     * authority over the batches assigned to <em>this</em> group (create / edit / close,
     * etc.) but no global permissions and no authority over other groups. Leadership is
     * per-group: the same user can lead group A and merely be a member of group B.
     *
     * <p>Invariant: every entry in {@code leaderUserIds} must also appear in
     * {@code userIds}. The admin Groups UI enforces this on save.
     *
     * <p>Pre-existing group rows that pre-date this field deserialize to an empty set,
     * which is correct — no leaders until an admin designates one.
     */
    private Set<String> leaderUserIds = new HashSet<>();

    private LocalDateTime createdAt;

    public Group() {
    }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }


    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public Set<String> getUserIds() {
        return userIds;
    }

    public void setUserIds(final Set<String> userIds) {
        this.userIds = userIds;
    }

    public Set<String> getLeaderUserIds() {
        if (leaderUserIds == null) leaderUserIds = new HashSet<>();
        return leaderUserIds;
    }

    public void setLeaderUserIds(final Set<String> leaderUserIds) {
        this.leaderUserIds = leaderUserIds == null ? new HashSet<>() : leaderUserIds;
    }
}
