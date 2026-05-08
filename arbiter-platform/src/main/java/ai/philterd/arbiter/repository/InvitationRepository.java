/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.repository;

import ai.philterd.arbiter.model.Invitation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InvitationRepository extends MongoRepository<Invitation, String> {

    /**
     * Token-hash lookup. Used at redemption time to find the invitation matching the
     * SHA-256 of the request-supplied token.
     */
    Optional<Invitation> findByTokenHash(String tokenHash);

    /**
     * Pending-email lookup. Used by the admin create flow to detect an outstanding
     * invitation for the same email so it can be replaced cleanly.
     */
    Optional<Invitation> findByEmail(String email);
}
