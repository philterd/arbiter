/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.security;

import ai.philterd.philter.model.LegalHoldRequest;
import ai.philterd.philter.model.LegalHoldResponse;
import ai.philterd.philter.model.ListOptions;
import ai.philterd.philter.model.OwnedLegalHoldResponse;
import ai.philterd.philter.model.Page;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Philter's legal holds, called with the signed-in person's session key, so Philter's per-user ownership applies
 * and its audit log names them. {@code owner} is another user's username, which Philter allows an administrator
 * only with {@code ADMIN_CROSS_USER_ACCESS_ENABLED=true}, or null for the person's own.
 */
@Service
public class PhilterHoldsService {

    private final PhilterSignInService signInService;

    public PhilterHoldsService(final PhilterSignInService signInService) {
        this.signInService = signInService;
    }

    public Page<LegalHoldResponse> list(final String key, final String owner, final ListOptions options)
            throws IOException {
        return signInService.client(key).listHolds(owner, options);
    }

    public Page<OwnedLegalHoldResponse> listAcrossUsers(final String key, final ListOptions options)
            throws IOException {
        return signInService.client(key).listHoldsAcrossUsers(options);
    }

    public LegalHoldResponse place(final String key, final LegalHoldRequest hold, final String owner)
            throws IOException {
        return signInService.client(key).createHold(hold, owner);
    }

    public void release(final String key, final String reference, final String owner) throws IOException {
        signInService.client(key).deleteHold(reference, owner);
    }
}
