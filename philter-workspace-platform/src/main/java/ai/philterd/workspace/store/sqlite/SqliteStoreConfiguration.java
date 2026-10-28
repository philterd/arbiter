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
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.Function;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;

/**
 * The SQLite store, selected with {@code philter-workspace.store=sqlite}. One database file holds every
 * collection. It uses write-ahead logging, so reads continue while a write is in progress, and
 * waits up to the busy timeout for the write lock instead of failing at once.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "sqlite")
@EnableSqliteRepositories(basePackages = "ai.philterd.workspace.repository")
public class SqliteStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SqliteStoreConfiguration.class);

    @Bean
    public DataSource sqliteDataSource(@Value("${philter-workspace.sqlite.path:./data/philter-workspace.db}") final String path,
                                       @Value("${philter-workspace.sqlite.pool-size:8}") final int poolSize,
                                       @Value("${philter-workspace.sqlite.busy-timeout-ms:30000}") final int busyTimeoutMs,
                                       @Value("${philter-workspace.sqlite.synchronous:FULL}") final String synchronous)
            throws IOException {
        final Path file = Path.of(path).toAbsolutePath();
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        final SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setBusyTimeout(busyTimeoutMs);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.valueOf(synchronous.trim().toUpperCase(Locale.ROOT)));

        final SQLiteDataSource connections = new SQLiteDataSource(sqlite) {
            @Override
            public SQLiteConnection getConnection(final String username, final String password) throws SQLException {
                final SQLiteConnection connection = super.getConnection(username, password);
                Function.create(connection, SqliteDocumentStore.FOLD, new Function() {
                    @Override
                    protected void xFunc() throws SQLException {
                        final String s = value_text(0);
                        if (s == null) {
                            result();
                        } else {
                            result(s.toLowerCase(Locale.ROOT));
                        }
                    }
                }, 1, Function.FLAG_DETERMINISTIC);
                return connection;
            }
        };
        connections.setUrl("jdbc:sqlite:" + file);

        final HikariConfig pool = new HikariConfig();
        pool.setPoolName("philter-workspace-sqlite");
        pool.setDataSource(connections);
        pool.setMaximumPoolSize(poolSize);
        log.info("Using the SQLite store at {}", file);
        return new HikariDataSource(pool);
    }

    @Bean(SqliteRepositoryConfigurationExtension.STORE_BEAN)
    public SqliteDocumentStore sqliteDocumentStore(final DataSource sqliteDataSource, final SymmetricCipher cipher) {
        return new SqliteDocumentStore(sqliteDataSource, cipher);
    }
}
