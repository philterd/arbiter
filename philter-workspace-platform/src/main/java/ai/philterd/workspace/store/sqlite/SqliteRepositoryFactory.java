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

import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.QueryMethodEvaluationContextProvider;

import java.util.Optional;

/** Creates Philter Workspace's repositories over {@link SqliteDocumentStore}. */
public class SqliteRepositoryFactory extends RepositoryFactorySupport {

    private final SqliteDocumentStore store;

    public SqliteRepositoryFactory(final SqliteDocumentStore store) {
        this.store = store;
    }

    @Override
    public <T, ID> EntityInformation<T, ID> getEntityInformation(final Class<T> domainClass) {
        return new SqliteEntityInformation<>(domainClass);
    }

    @Override
    protected Object getTargetRepository(final RepositoryInformation information) {
        // Create the table and indexes at startup, so a bad mapping fails fast.
        store.register(information.getDomainType());
        return new SimpleSqliteRepository<>(information.getDomainType(), store);
    }

    @Override
    protected Class<?> getRepositoryBaseClass(final RepositoryMetadata metadata) {
        return SimpleSqliteRepository.class;
    }

    @Override
    protected Optional<QueryLookupStrategy> getQueryLookupStrategy(final QueryLookupStrategy.Key key,
                                                                   final QueryMethodEvaluationContextProvider provider) {
        return Optional.of((method, metadata, projections, namedQueries) -> {
            if (method.isAnnotationPresent(org.springframework.data.mongodb.repository.Query.class)) {
                throw new IllegalStateException(metadata.getRepositoryInterface().getSimpleName() + "." + method.getName()
                        + " uses a MongoDB @Query; the SQLite store supports derived queries only");
            }
            return new PartTreeSqliteQuery(new QueryMethod(method, metadata, projections), store);
        });
    }
}
