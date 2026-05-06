/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.InboxMessage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InboxMessageRepository extends MongoRepository<InboxMessage, String> {

    List<InboxMessage> findByUserIdOrderByCreatedAtDesc(String userId);

    // $ne: true matches both false and absent (pre-archive) documents
    @Query(value = "{ 'userId': ?0, 'archived': { '$ne': true } }", sort = "{ 'createdAt': -1 }")
    List<InboxMessage> findNonArchivedByUserId(String userId);

    @Query(value = "{ 'userId': ?0, 'read': false, 'archived': { '$ne': true } }", count = true)
    long countUnreadNonArchivedByUserId(String userId);
}
