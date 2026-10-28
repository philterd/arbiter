/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.repository;

import ai.philterd.workspace.model.InboxMessage;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InboxMessageRepository extends WorkspaceRepository<InboxMessage, String> {

    List<InboxMessage> findByUserIdOrderByCreatedAtDesc(String userId);

    // ArchivedNot(true) also matches messages saved before the archived field existed.
    List<InboxMessage> findByUserIdAndArchivedNotOrderByCreatedAtDesc(String userId, boolean archived);

    long countByUserIdAndReadAndArchivedNot(String userId, boolean read, boolean archived);

    default List<InboxMessage> findNonArchivedByUserId(final String userId) {
        return findByUserIdAndArchivedNotOrderByCreatedAtDesc(userId, true);
    }

    default long countUnreadNonArchivedByUserId(final String userId) {
        return countByUserIdAndReadAndArchivedNot(userId, false, true);
    }
}
