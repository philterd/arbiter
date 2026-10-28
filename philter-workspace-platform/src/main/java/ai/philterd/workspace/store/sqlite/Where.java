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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A SQL condition over a collection table, with its bind parameters. Field paths are
 * entity property paths such as {@code status} or {@code location.characterStart}.
 */
public record Where(String sql, List<Object> params) {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public static final Where ALL = new Where("1 = 1", List.of());

    /** The SQL expression reading {@code path} from the stored document. */
    public static String field(final String path) {
        if ("id".equals(path)) {
            return "id";
        }
        for (String segment : path.split("\\.", -1)) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("Invalid field path: " + path);
            }
        }
        return "json_extract(doc, '$." + path + "')";
    }

    public static Where eq(final String path, final Object value) {
        return value == null
                ? new Where(field(path) + " IS NULL", List.of())
                : new Where(field(path) + " = ?", List.of(value));
    }

    /** Not equal, also matching documents where the field is missing, as MongoDB's {@code $ne} does. */
    public static Where ne(final String path, final Object value) {
        return new Where(field(path) + " IS NOT ?", listOf(value));
    }

    public static Where in(final String path, final Collection<?> values) {
        if (values.isEmpty()) {
            return new Where("0 = 1", List.of());
        }
        return new Where(field(path) + " IN (" + placeholders(values.size()) + ")", new ArrayList<>(values));
    }

    /** Not in, also matching documents where the field is missing, as MongoDB's {@code $nin} does. */
    public static Where notIn(final String path, final Collection<?> values) {
        if (values.isEmpty()) {
            return ALL;
        }
        final String f = field(path);
        return new Where("(" + f + " IS NULL OR " + f + " NOT IN (" + placeholders(values.size()) + "))",
                new ArrayList<>(values));
    }

    public static Where cmp(final String path, final String operator, final Object value) {
        return new Where(field(path) + " " + operator + " ?", listOf(value));
    }

    public static Where and(final Where... parts) {
        return join(" AND ", List.of(parts));
    }

    public static Where and(final List<Where> parts) {
        return join(" AND ", parts);
    }

    public static Where or(final List<Where> parts) {
        return parts.isEmpty() ? new Where("0 = 1", List.of()) : join(" OR ", parts);
    }

    private static Where join(final String op, final List<Where> parts) {
        if (parts.isEmpty()) {
            return ALL;
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        final StringBuilder sql = new StringBuilder();
        final List<Object> params = new ArrayList<>();
        for (Where w : parts) {
            if (!sql.isEmpty()) {
                sql.append(op);
            }
            sql.append('(').append(w.sql()).append(')');
            params.addAll(w.params());
        }
        return new Where(sql.toString(), params);
    }

    static String placeholders(final int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    private static List<Object> listOf(final Object value) {
        final List<Object> l = new ArrayList<>(1);
        l.add(value);
        return l;
    }
}
