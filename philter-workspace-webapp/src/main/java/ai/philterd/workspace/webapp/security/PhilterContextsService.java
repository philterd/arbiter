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

import ai.philterd.philter.model.ContextDetails;
import ai.philterd.philter.model.GetContextsAcrossUsersResponse;
import ai.philterd.philter.model.GetContextsResponse;
import ai.philterd.philter.model.ListOptions;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Philter's Contexts API, called with the signed-in person's session key, so Philter's per-user ownership
 * applies and its audit log names them. {@code owner} is another user's username, which Philter allows an
 * administrator only with {@code ADMIN_CROSS_USER_ACCESS_ENABLED=true}, or null for the person's own.
 */
@Service
public class PhilterContextsService {

    private final PhilterSignInService signInService;

    public PhilterContextsService(final PhilterSignInService signInService) {
        this.signInService = signInService;
    }

    public GetContextsResponse list(final String key, final String owner, final ListOptions options)
            throws IOException {
        return signInService.client(key).listContexts(owner, options);
    }

    public GetContextsAcrossUsersResponse listAcrossUsers(final String key, final ListOptions options)
            throws IOException {
        return signInService.client(key).listContextsAcrossUsers(options);
    }

    /** A context's settings, its size, and its entries counted by filter type. */
    public ContextDetails details(final String key, final String name, final String owner) throws IOException {
        return signInService.client(key).getContextDetails(name, owner);
    }

    public void create(final String key, final String name, final boolean disambiguation,
                       final String disambiguationScope, final boolean ledger, final String owner) throws IOException {
        signInService.client(key).createContext(name, disambiguation, disambiguationScope, ledger, owner);
    }

    /** Changes the settings that are not null; Philter keeps the others. */
    public void update(final String key, final String name, final Boolean disambiguation,
                       final String disambiguationScope, final Boolean ledger, final String owner) throws IOException {
        signInService.client(key).updateContext(name, disambiguation, disambiguationScope, ledger, owner);
    }

    /** Deletes every entry in a context, and what span disambiguation learned for it. */
    public void clear(final String key, final String name, final String owner) throws IOException {
        signInService.client(key).deleteContextEntries(name, owner);
    }

    public void delete(final String key, final String name, final String owner) throws IOException {
        signInService.client(key).deleteContext(name, owner);
    }
}
