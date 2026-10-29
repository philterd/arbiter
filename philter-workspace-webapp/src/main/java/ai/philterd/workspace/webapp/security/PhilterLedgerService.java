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

import ai.philterd.philter.model.GetLedgerResponse;
import ai.philterd.philter.model.LedgerChain;
import ai.philterd.philter.model.LedgerExport;
import ai.philterd.philter.model.ListOptions;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Philter's redaction ledger, called with the signed-in person's session key, so Philter's per-user ownership
 * applies and its audit log names them. {@code owner} is another user's username, which Philter allows an
 * administrator only with {@code ADMIN_CROSS_USER_ACCESS_ENABLED=true}, or null for the person's own.
 */
@Service
public class PhilterLedgerService {

    private final PhilterSignInService signInService;

    public PhilterLedgerService(final PhilterSignInService signInService) {
        this.signInService = signInService;
    }

    /** Each chain's first entry, newest first unless sorted; {@code q} matches a document ID or filename. */
    public GetLedgerResponse chains(final String key, final String owner, final ListOptions options)
            throws IOException {
        return signInService.client(key).listLedgerChains(owner, options);
    }

    public GetLedgerResponse chainsAcrossUsers(final String key, final ListOptions options) throws IOException {
        return signInService.client(key).listLedgerChainsAcrossUsers(options);
    }

    /** A page of a chain's entries, without their original values, and whether the whole chain verifies. */
    public LedgerChain chain(final String key, final String documentId, final String owner, final int offset,
                             final int limit) throws IOException {
        return signInService.client(key).getLedgerChain(documentId, owner, offset, limit);
    }

    /** The whole chain with the original values, which are personal information. */
    public LedgerExport export(final String key, final String documentId, final String owner) throws IOException {
        return signInService.client(key).getLedgerExport(documentId, owner);
    }

    /** Deletes a document's whole chain; Philter has no call to delete one entry. */
    public void delete(final String key, final String documentId, final String owner) throws IOException {
        signInService.client(key).deleteLedgerEntry(documentId, owner);
    }
}
