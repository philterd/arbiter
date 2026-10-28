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

import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;

public class SqliteRepositoryFactoryBean<T extends Repository<S, ID>, S, ID> extends RepositoryFactoryBeanSupport<T, S, ID> {

    private SqliteDocumentStore store;

    public SqliteRepositoryFactoryBean(final Class<? extends T> repositoryInterface) {
        super(repositoryInterface);
    }

    public void setStore(final SqliteDocumentStore store) {
        this.store = store;
    }

    @Override
    protected RepositoryFactorySupport createRepositoryFactory() {
        return new SqliteRepositoryFactory(store);
    }
}
