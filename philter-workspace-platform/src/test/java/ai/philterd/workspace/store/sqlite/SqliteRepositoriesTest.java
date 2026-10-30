/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.store.sqlite;

import ai.philterd.workspace.model.User;
import ai.philterd.workspace.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQLite repository behaviour that needs the raw stored rows; the shared behaviour is in {@code SqliteStoreContractTest}. */
class SqliteRepositoriesTest {

    @TempDir
    Path dir;

    private SqliteTestStore db;

    @BeforeEach
    void open() throws Exception {
        db = new SqliteTestStore(dir);
    }

    @AfterEach
    void close() throws Exception {
        db.close();
    }

    @Test
    void localCredentialsAreRemovedFromStoredUserRowsOnce() {
        final UserRepository users = db.repository(UserRepository.class);
        final ai.philterd.workspace.repository.GeneralSettingsRepository settings =
                db.repository(ai.philterd.workspace.repository.GeneralSettingsRepository.class);
        final User u = new User();
        u.setEmail("reviewer@example.com");
        u.setRoles(java.util.Set.of("USER"));
        users.save(u);
        // A row as local sign-in left it, with credentials the model no longer has.
        final String old = db.rawDoc("users", u.getId()).replaceFirst("\\{",
                "{\"passwordHash\":\"{bcrypt}hash-marker\",\"totpSecret\":\"totp-marker\",\"apiKey\":\"key-marker\",");
        db.jdbc.update("UPDATE \"users\" SET doc = ? WHERE id = ?", old, u.getId());
        assertTrue(db.rawDoc("users", u.getId()).contains("hash-marker"));

        new ai.philterd.workspace.service.LocalCredentialsCleanup(users, settings).run(null);

        final String raw = db.rawDoc("users", u.getId());
        assertFalse(raw.contains("hash-marker") || raw.contains("totp-marker") || raw.contains("key-marker"), raw);
        assertEquals("reviewer@example.com", users.findById(u.getId()).orElseThrow().getEmail());
        assertTrue(settings.findById(ai.philterd.workspace.model.GeneralSettings.SINGLETON_ID).orElseThrow()
                .isLocalCredentialsRemoved());

        // Runs once: a later start leaves rows alone.
        final Long version = users.findById(u.getId()).orElseThrow().getVersion();
        new ai.philterd.workspace.service.LocalCredentialsCleanup(users, settings).run(null);
        assertEquals(version, users.findById(u.getId()).orElseThrow().getVersion());
    }
}
