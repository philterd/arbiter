/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.store.sqlite;

import org.springframework.data.repository.core.support.AbstractEntityInformation;

/** Entity id and newness for the SQLite store, read from the same fields MongoDB uses. */
final class SqliteEntityInformation<T, ID> extends AbstractEntityInformation<T, ID> {

    private final CollectionInfo info;

    SqliteEntityInformation(final Class<T> domainClass) {
        super(domainClass);
        this.info = new CollectionInfo(domainClass);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ID getId(final T entity) {
        return (ID) info.id(entity);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<ID> getIdType() {
        return (Class<ID>) info.idField.getType();
    }

    @Override
    public boolean isNew(final T entity) {
        if (info.versionField != null) {
            return info.version(entity) == null;
        }
        return info.id(entity) == null;
    }
}
