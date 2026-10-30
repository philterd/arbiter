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

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Table, id, version, and indexes for one entity class. The collection name and indexes come
 * from the same annotations MongoDB reads, so both stores index the same fields.
 */
final class CollectionInfo {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]+");
    private static final JsonMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .build();

    record IndexDef(String name, List<String> paths, boolean unique, Where partialFilter) {
    }

    final Class<?> type;
    final String table;
    final Field idField;
    final Field versionField;
    final List<IndexDef> indexes;

    CollectionInfo(final Class<?> type) {
        this.type = type;
        final Document document = type.getAnnotation(Document.class);
        final String collection = document != null && !document.collection().isBlank()
                ? document.collection()
                : Character.toLowerCase(type.getSimpleName().charAt(0)) + type.getSimpleName().substring(1);
        if (!NAME.matcher(collection).matches()) {
            throw new IllegalStateException("Unsupported collection name for " + type.getSimpleName());
        }
        this.table = collection;

        Field id = null;
        Field version = null;
        final List<IndexDef> defs = new ArrayList<>();
        for (Field f : allFields(type)) {
            if (f.isAnnotationPresent(Id.class) || (id == null && f.getName().equals("id"))) {
                id = f;
            }
            if (f.isAnnotationPresent(Version.class)) {
                version = f;
            }
            final Indexed indexed = f.getAnnotation(Indexed.class);
            if (indexed != null) {
                // A SQLite unique index never counts a missing value, so it behaves as the sparse
                // unique indexes MongoDB is given: any number of records may leave the field empty.
                defs.add(new IndexDef("ix_" + table + "_" + f.getName(), List.of(f.getName()), indexed.unique(), null));
            }
        }
        if (id == null) {
            throw new IllegalStateException(type.getSimpleName() + " has no id field");
        }
        id.setAccessible(true);
        if (version != null) {
            version.setAccessible(true);
        }
        this.idField = id;
        this.versionField = version;

        final List<CompoundIndex> compounds = new ArrayList<>();
        final CompoundIndexes many = type.getAnnotation(CompoundIndexes.class);
        if (many != null) {
            compounds.addAll(List.of(many.value()));
        }
        final CompoundIndex one = type.getAnnotation(CompoundIndex.class);
        if (one != null) {
            compounds.add(one);
        }
        for (CompoundIndex ci : compounds) {
            final List<String> paths = new ArrayList<>();
            parse(ci.def()).fieldNames().forEachRemaining(paths::add);
            final String name = ci.name().isBlank() ? String.join("_", paths) : ci.name();
            if (!NAME.matcher(name).matches()) {
                throw new IllegalStateException("Unsupported index name on " + type.getSimpleName());
            }
            final Where partial = ci.partialFilter().isBlank() ? null : partialFilter(parse(ci.partialFilter()));
            defs.add(new IndexDef("ix_" + table + "_" + name, paths, ci.unique(), partial));
        }
        this.indexes = List.copyOf(defs);
    }

    String id(final Object entity) {
        try {
            final Object v = idField.get(entity);
            return v == null ? null : v.toString();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    void setId(final Object entity, final String id) {
        try {
            idField.set(entity, id);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    Long version(final Object entity) {
        try {
            final Object v = versionField.get(entity);
            return v == null ? null : ((Number) v).longValue();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    void setVersion(final Object entity, final Long version) {
        try {
            final Class<?> t = versionField.getType();
            if (t == Long.class || t == long.class) {
                versionField.set(entity, version);
            } else if (t == Integer.class || t == int.class) {
                versionField.set(entity, version == null ? null : version.intValue());
            } else {
                throw new IllegalStateException("Unsupported @Version type on " + type.getSimpleName());
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Field> allFields(final Class<?> type) {
        final List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    fields.add(f);
                }
            }
        }
        return fields;
    }

    private static JsonNode parse(final String mongoJson) {
        try {
            return LENIENT.readTree(mongoJson);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read index definition: " + mongoJson, e);
        }
    }

    /** Supports the subset of MongoDB filters used in partial indexes: equality and {@code $in}. */
    private static Where partialFilter(final JsonNode filter) {
        final List<Where> parts = new ArrayList<>();
        final Iterator<Map.Entry<String, JsonNode>> it = filter.fields();
        while (it.hasNext()) {
            final Map.Entry<String, JsonNode> e = it.next();
            final JsonNode v = e.getValue();
            if (v.isObject() && v.has("$in") && v.size() == 1) {
                final List<String> values = new ArrayList<>();
                v.get("$in").forEach(n -> values.add(literal(n)));
                parts.add(new Where(Where.field(e.getKey()) + " IN (" + String.join(", ", values) + ")", List.of()));
            } else if (v.isValueNode()) {
                parts.add(new Where(Where.field(e.getKey()) + " = " + literal(v), List.of()));
            } else {
                throw new IllegalStateException("Unsupported partial index filter on field " + e.getKey());
            }
        }
        return Where.and(parts);
    }

    /** Index definitions can't take bind parameters, so values are inlined; only plain strings, numbers, and booleans. */
    private static String literal(final JsonNode n) {
        if (n.isTextual()) {
            if (!NAME.matcher(n.asText()).matches()) {
                throw new IllegalStateException("Unsupported partial index value: " + n.asText());
            }
            return "'" + n.asText() + "'";
        }
        if (n.isNumber()) {
            return n.asText();
        }
        if (n.isBoolean()) {
            return n.asBoolean() ? "1" : "0";
        }
        throw new IllegalStateException("Unsupported partial index value");
    }
}
