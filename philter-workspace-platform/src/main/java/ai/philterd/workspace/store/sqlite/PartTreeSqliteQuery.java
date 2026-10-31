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

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.core.PropertyPath;
import org.springframework.data.repository.query.ParametersParameterAccessor;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Runs a derived query method ({@code findByBatchIdAndStatusIn}, {@code countByRolesContaining},
 * and so on) as SQL over the stored JSON, matching MongoDB's results for the same method:
 * negations also match documents missing the field, and a condition on a collection field
 * matches when any element does.
 *
 * <p>Unsupported keywords are refused when the repository is created, not when the query runs.</p>
 */
final class PartTreeSqliteQuery implements RepositoryQuery {

    private static final Set<Part.Type> SUPPORTED = EnumSet.of(
            Part.Type.SIMPLE_PROPERTY, Part.Type.NEGATING_SIMPLE_PROPERTY, Part.Type.IN, Part.Type.NOT_IN,
            Part.Type.IS_NULL, Part.Type.IS_NOT_NULL, Part.Type.CONTAINING, Part.Type.NOT_CONTAINING,
            Part.Type.STARTING_WITH, Part.Type.GREATER_THAN, Part.Type.GREATER_THAN_EQUAL, Part.Type.AFTER,
            Part.Type.LESS_THAN, Part.Type.LESS_THAN_EQUAL, Part.Type.BEFORE, Part.Type.BETWEEN,
            Part.Type.TRUE, Part.Type.FALSE, Part.Type.EXISTS);

    private final QueryMethod method;
    private final PartTree tree;
    private final Class<?> domainType;
    private final SqliteDocumentStore store;

    PartTreeSqliteQuery(final QueryMethod method, final SqliteDocumentStore store) {
        this.method = method;
        this.store = store;
        this.domainType = method.getEntityInformation().getJavaType();
        this.tree = new PartTree(method.getName(), domainType);
        final String name = domainType.getSimpleName() + "." + method.getName();
        if (method.isSliceQuery() || method.isStreamQuery() || method.isScrollQuery() || tree.isDistinct()) {
            throw new IllegalStateException(name + ": slice, stream, scroll, and distinct queries are not supported by the SQLite store");
        }
        for (Part part : tree.getParts()) {
            if (!SUPPORTED.contains(part.getType())) {
                throw new IllegalStateException(name + ": " + part.getType() + " is not supported by the SQLite store");
            }
            for (PropertyPath p = part.getProperty(); p.hasNext(); p = p.next()) {
                if (p.isCollection()) {
                    throw new IllegalStateException(name + ": conditions inside collection elements are not supported by the SQLite store");
                }
            }
            Where.field(part.getProperty().toDotPath());
        }
    }

    @Override
    public QueryMethod getQueryMethod() {
        return method;
    }

    @Override
    public Object execute(final Object[] parameters) {
        final ParametersParameterAccessor accessor = new ParametersParameterAccessor(method.getParameters(), parameters);
        final Iterator<Object> values = accessor.iterator();
        final List<Where> ors = new ArrayList<>();
        for (PartTree.OrPart or : tree) {
            final List<Where> ands = new ArrayList<>();
            for (Part part : or) {
                ands.add(where(part, values));
            }
            ors.add(Where.and(ands));
        }
        final Where where = tree.hasPredicate() ? Where.or(ors) : Where.ALL;

        final Pageable pageable = accessor.getPageable();
        final Sort sort = tree.getSort().and(accessor.getSort());
        long limit = -1;
        long offset = 0;
        if (pageable.isPaged()) {
            limit = pageable.getPageSize();
            offset = pageable.getOffset();
        }
        if (tree.isLimiting()) {
            limit = limit < 0 ? tree.getMaxResults() : Math.min(limit, tree.getMaxResults());
        }

        if (tree.isDelete()) {
            return method.isCollectionQuery()
                    ? store.deleteReturning(domainType, where)
                    : store.delete(domainType, where);
        }
        if (tree.isCountProjection()) {
            return store.count(domainType, where);
        }
        if (tree.isExistsProjection()) {
            return store.exists(domainType, where);
        }
        if (method.isPageQuery()) {
            final List<?> content = store.find(domainType, where, sort, limit, offset);
            return PageableExecutionUtils.getPage(content, pageable, () -> store.count(domainType, where));
        }
        if (method.isCollectionQuery()) {
            return store.find(domainType, where, sort, limit, offset);
        }
        final List<?> first = store.find(domainType, where, sort, 1, offset);
        return first.isEmpty() ? null : first.get(0);
    }

    private Where where(final Part part, final Iterator<Object> values) {
        final PropertyPath leaf = part.getProperty().getLeafProperty();
        final String path = part.getProperty().toDotPath();
        final boolean many = leaf.isCollection();
        final boolean fold = part.shouldIgnoreCase() != Part.IgnoreCaseType.NEVER && leaf.getType() == String.class;
        final String f = Where.field(path);
        final String col = fold ? fold(f) : f;
        final String q = fold ? fold("?") : "?";

        return switch (part.getType()) {
            case SIMPLE_PROPERTY -> {
                final Object v = values.next();
                if (many) {
                    yield anyElement(path, fold, " = " + q, List.of(v));
                }
                yield v == null ? Where.eq(path, null) : new Where(col + " = " + q, params(v));
            }
            case NEGATING_SIMPLE_PROPERTY -> {
                final Object v = values.next();
                yield many ? not(anyElement(path, fold, " = " + q, List.of(v))) : new Where(col + " IS NOT " + q, params(v));
            }
            case IN, NOT_IN -> {
                final List<Object> vs = list(values.next());
                final boolean in = part.getType() == Part.Type.IN;
                if (vs.isEmpty()) {
                    yield in ? new Where("0 = 1", List.of()) : Where.ALL;
                }
                final String set = " IN (" + String.join(", ", java.util.Collections.nCopies(vs.size(), q)) + ")";
                if (many) {
                    final Where any = anyElement(path, fold, set, vs);
                    yield in ? any : not(any);
                }
                yield in ? new Where(col + set, vs) : new Where("(" + f + " IS NULL OR " + col + " NOT" + set + ")", vs);
            }
            case IS_NULL -> new Where(f + " IS NULL", List.of());
            case IS_NOT_NULL -> new Where(f + " IS NOT NULL", List.of());
            case CONTAINING -> {
                final Object v = values.next();
                yield many ? anyElement(path, fold, " = " + q, List.of(v)) : new Where("instr(" + col + ", " + q + ") > 0", params(v));
            }
            case NOT_CONTAINING -> {
                final Object v = values.next();
                yield many ? not(anyElement(path, fold, " = " + q, List.of(v)))
                        : new Where("(" + f + " IS NULL OR instr(" + col + ", " + q + ") = 0)", params(v));
            }
            case STARTING_WITH -> new Where("instr(" + col + ", " + q + ") = 1", params(values.next()));
            case GREATER_THAN, AFTER -> new Where(f + " > ?", params(values.next()));
            case GREATER_THAN_EQUAL -> new Where(f + " >= ?", params(values.next()));
            case LESS_THAN, BEFORE -> new Where(f + " < ?", params(values.next()));
            case LESS_THAN_EQUAL -> new Where(f + " <= ?", params(values.next()));
            case BETWEEN -> {
                // Exclusive at both ends, as MongoDB's derived Between is.
                final Object low = values.next();
                final Object high = values.next();
                yield new Where(f + " > ? AND " + f + " < ?", params(low, high));
            }
            case TRUE -> new Where(f + " = 1", List.of());
            case FALSE -> new Where(f + " = 0", List.of());
            case EXISTS -> {
                final boolean exists = Boolean.TRUE.equals(values.next());
                final String present = "id".equals(path) ? "id" : "json_type(doc, '$." + path + "')";
                yield new Where(present + (exists ? " IS NOT NULL" : " IS NULL"), List.of());
            }
            default -> throw new IllegalStateException(part.getType() + " is not supported by the SQLite store");
        };
    }

    /** Matches when any element of the collection at {@code path} satisfies {@code condition}. */
    private static Where anyElement(final String path, final boolean fold, final String condition, final List<?> vs) {
        Where.field(path);
        final String value = fold ? fold("value") : "value";
        return new Where("EXISTS (SELECT 1 FROM json_each(doc, '$." + path + "') WHERE " + value + condition + ")",
                new ArrayList<>(vs));
    }

    private static Where not(final Where w) {
        return new Where("NOT (" + w.sql() + ")", w.params());
    }

    private static String fold(final String expr) {
        return SqliteDocumentStore.FOLD + "(" + expr + ")";
    }

    private static List<Object> params(final Object... vs) {
        return new ArrayList<>(Arrays.asList(vs));
    }

    private static List<Object> list(final Object value) {
        if (value instanceof Collection<?> c) {
            return new ArrayList<>(c);
        }
        if (value instanceof Object[] a) {
            return new ArrayList<>(Arrays.asList(a));
        }
        return params(value);
    }
}
