/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.store;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreEnvironmentPostProcessorTest {

    private static String excludes(final MockEnvironment env) {
        new StoreEnvironmentPostProcessor().postProcessEnvironment(env, null);
        return env.getProperty("spring.autoconfigure.exclude", "");
    }

    @Test
    void sqliteTurnsOffMongoAndKeepsExistingExclusions() {
        final String excluded = excludes(new MockEnvironment()
                .withProperty("philter-workspace.store", "sqlite")
                .withProperty("spring.autoconfigure.exclude", "com.example.Other"));
        assertTrue(excluded.contains("com.example.Other"));
        assertTrue(excluded.contains("org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration"));
        assertFalse(excluded.contains("DataSourceAutoConfiguration"));
    }

    @Test
    void mongodbIsTheDefaultAndTurnsOffJdbc() {
        final String excluded = excludes(new MockEnvironment());
        assertTrue(excluded.contains("org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"));
        assertFalse(excluded.contains("Mongo"));
    }

    @Test
    void unknownStoreIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> excludes(new MockEnvironment().withProperty("philter-workspace.store", "postgres")));
    }
}
