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

import ai.philterd.philter.model.AdminSettings;
import ai.philterd.philter.model.AuditLogExport;
import ai.philterd.philter.model.GetAuditLogResponse;
import ai.philterd.philter.model.ListOptions;
import ai.philterd.philter.model.SigningKey;
import ai.philterd.philter.model.UpdateAdminSettingsRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Philter's deployment settings and audit log, called with the signed-in administrator's session key, so Philter
 * checks that they are a Philter administrator and its audit log names them.
 */
@Service
public class PhilterAdminService {

    private final PhilterSignInService signInService;

    public PhilterAdminService(final PhilterSignInService signInService) {
        this.signInService = signInService;
    }

    public AdminSettings settings(final String key) throws IOException {
        return signInService.client(key).getAdminSettings();
    }

    /** Changes the settings that are not null; Philter keeps the others. */
    public AdminSettings update(final String key, final UpdateAdminSettingsRequest request) throws IOException {
        return signInService.client(key).updateAdminSettings(request);
    }

    /** The public key Philter signs with, and its fingerprint. */
    public SigningKey signingKey(final String key) throws IOException {
        return signInService.client(key).getSigningKeyDetails();
    }

    /**
     * A page of audit events, newest first. {@code event}, {@code from} (inclusive), {@code to} (exclusive), and
     * {@code username} (the acting user) are each optional.
     */
    public GetAuditLogResponse events(final String key, final String event, final Instant from, final Instant to,
                                      final String username, final ListOptions options) throws IOException {
        return signInService.client(key).getAuditLog(event, from, to, username, options);
    }

    /** One page of the CSV export of whole days, read in UTC; {@code cursor} is null for the first page. */
    public AuditLogExport exportPage(final String key, final LocalDate from, final LocalDate to, final String cursor,
                                     final int limit) throws IOException {
        return signInService.client(key).exportAuditLogAfter(from, to, ZoneOffset.UTC, cursor, limit);
    }
}
