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

import ai.philterd.philter.model.CustomList;
import ai.philterd.philter.model.CustomListSummary;
import ai.philterd.philter.model.ListOptions;
import ai.philterd.philter.model.Page;
import ai.philterd.philter.model.RedactList;
import ai.philterd.philter.model.RedactListTerms;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Philter's custom lists and its always and never redact lists, called with the signed-in person's session key,
 * so Philter's per-user ownership applies and its audit log names them. {@code owner} is another user's
 * username, which Philter allows an administrator only with {@code ADMIN_CROSS_USER_ACCESS_ENABLED=true}, or
 * null for the person's own.
 */
@Service
public class PhilterListsService {

    private final PhilterSignInService signInService;

    public PhilterListsService(final PhilterSignInService signInService) {
        this.signInService = signInService;
    }

    public Page<CustomListSummary> list(final String key, final String owner, final ListOptions options)
            throws IOException {
        return signInService.client(key).listCustomLists(owner, options);
    }

    public Page<CustomListSummary> listAcrossUsers(final String key, final ListOptions options) throws IOException {
        return signInService.client(key).listCustomListsAcrossUsers(options);
    }

    public CustomList get(final String key, final String name, final String owner) throws IOException {
        return signInService.client(key).getList(name, owner);
    }

    /** Creates a list; a name the owner already uses is refused with {@code list_exists}. */
    public void create(final String key, final String name, final String description, final List<String> items,
                       final String owner) throws IOException {
        signInService.client(key).saveList(name, description, items, owner);
    }

    /** Replaces a list's items and, when not null, its description. */
    public void replace(final String key, final String name, final String description, final List<String> items,
                        final String owner) throws IOException {
        signInService.client(key).replaceList(name, description, items, owner);
    }

    public void delete(final String key, final String name, final String owner) throws IOException {
        signInService.client(key).deleteList(name, owner);
    }

    public RedactListTerms getRedactList(final String key, final RedactList list, final String owner)
            throws IOException {
        return signInService.client(key).getRedactList(list, owner);
    }

    /** Replaces one redact list, only if it is still at {@code revision}. */
    public RedactListTerms replaceRedactList(final String key, final RedactList list, final List<String> terms,
                                             final long revision, final String owner) throws IOException {
        return signInService.client(key).replaceRedactList(list, terms, revision, owner);
    }
}
