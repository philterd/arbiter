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

import ai.philterd.workspace.service.SymmetricCipher;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Base64;

/** A SQLite store on a real database file, built the way the application builds it. */
final class SqliteTestStore implements AutoCloseable {

    static final SymmetricCipher CIPHER = new SymmetricCipher(Base64.getEncoder().encodeToString(new byte[32]));

    final DataSource dataSource;
    final SqliteDocumentStore store;
    final SqliteRepositoryFactory factory;
    final JdbcTemplate jdbc;

    SqliteTestStore(final Path dir) throws Exception {
        dataSource = new SqliteStoreConfiguration().sqliteDataSource(dir.resolve("philter-workspace.db").toString(), 8, 10_000, "NORMAL");
        store = new SqliteDocumentStore(dataSource, CIPHER);
        factory = new SqliteRepositoryFactory(store);
        jdbc = new JdbcTemplate(dataSource);
    }

    <R> R repository(final Class<R> type) {
        return factory.getRepository(type);
    }

    /** Raw stored JSON, to check what is written to disk. */
    String rawDoc(final String table, final String id) {
        return jdbc.queryForObject("SELECT doc FROM \"" + table + "\" WHERE id = ?", String.class, id);
    }

    @Override
    public void close() throws Exception {
        if (dataSource instanceof AutoCloseable c) {
            c.close();
        }
    }
}
