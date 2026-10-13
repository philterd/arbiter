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

import ai.philterd.arbiter.repository.ArbiterRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The CRUD and paging methods every Arbiter repository inherits, backed by {@link SqliteDocumentStore}. */
public class SimpleSqliteRepository<T, ID> implements ArbiterRepository<T, ID> {

    private final Class<T> type;
    private final SqliteDocumentStore store;

    public SimpleSqliteRepository(final Class<T> type, final SqliteDocumentStore store) {
        this.type = type;
        this.store = store;
    }

    @Override
    public <S extends T> S save(final S entity) {
        return store.save(entity);
    }

    @Override
    public <S extends T> List<S> saveAll(final Iterable<S> entities) {
        final List<S> saved = new ArrayList<>();
        for (S e : entities) {
            saved.add(store.save(e));
        }
        return saved;
    }

    @Override
    public Optional<T> findById(final ID id) {
        return store.findById(type, id.toString());
    }

    @Override
    public boolean existsById(final ID id) {
        return store.exists(type, Where.eq("id", id.toString()));
    }

    @Override
    public List<T> findAll() {
        return store.find(type, Where.ALL, Sort.unsorted(), -1, 0);
    }

    @Override
    public List<T> findAllById(final Iterable<ID> ids) {
        return store.find(type, Where.in("id", strings(ids)), Sort.unsorted(), -1, 0);
    }

    @Override
    public long count() {
        return store.count(type, Where.ALL);
    }

    @Override
    public void deleteById(final ID id) {
        store.delete(type, Where.eq("id", id.toString()));
    }

    @Override
    public void delete(final T entity) {
        store.delete(entity);
    }

    @Override
    public void deleteAllById(final Iterable<? extends ID> ids) {
        store.delete(type, Where.in("id", strings(ids)));
    }

    @Override
    public void deleteAll(final Iterable<? extends T> entities) {
        for (T e : entities) {
            store.delete(e);
        }
    }

    @Override
    public void deleteAll() {
        store.delete(type, Where.ALL);
    }

    @Override
    public List<T> findAll(final Sort sort) {
        return store.find(type, Where.ALL, sort, -1, 0);
    }

    @Override
    public Page<T> findAll(final Pageable pageable) {
        if (pageable.isUnpaged()) {
            return PageableExecutionUtils.getPage(findAll(pageable.getSort()), pageable, this::count);
        }
        final List<T> content = store.find(type, Where.ALL, pageable.getSort(), pageable.getPageSize(), pageable.getOffset());
        return PageableExecutionUtils.getPage(content, pageable, this::count);
    }

    private static List<String> strings(final Iterable<?> ids) {
        final List<String> out = new ArrayList<>();
        for (Object id : ids) {
            out.add(id.toString());
        }
        return out;
    }
}
