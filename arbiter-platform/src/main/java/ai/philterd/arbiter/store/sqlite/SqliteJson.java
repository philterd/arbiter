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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * JSON form of stored entities, and the SQL form of values compared against it.
 *
 * <p>Entities are mapped by field, as MongoDB maps them, regardless of Jackson annotations. Timestamps are written at millisecond
 * precision (as MongoDB stores them) in a fixed-width format, so comparing the stored strings
 * orders them the same as comparing the timestamps.</p>
 */
final class SqliteJson {

    static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS");
    static final DateTimeFormatter INSTANT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper;

    SqliteJson() {
        final SimpleModule times = new SimpleModule("arbiter-sqlite-times");
        times.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(final Instant v, final JsonGenerator g, final SerializerProvider p) throws IOException {
                g.writeString(INSTANT.format(v));
            }
        });
        times.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(final JsonParser p, final DeserializationContext c) throws IOException {
                return Instant.parse(p.getValueAsString());
            }
        });
        times.addSerializer(LocalDateTime.class, new JsonSerializer<>() {
            @Override
            public void serialize(final LocalDateTime v, final JsonGenerator g, final SerializerProvider p) throws IOException {
                g.writeString(LOCAL_DATE_TIME.format(v));
            }
        });
        times.addDeserializer(LocalDateTime.class, new JsonDeserializer<>() {
            @Override
            public LocalDateTime deserialize(final JsonParser p, final DeserializationContext c) throws IOException {
                return LocalDateTime.parse(p.getValueAsString());
            }
        });
        times.addSerializer(Date.class, new JsonSerializer<>() {
            @Override
            public void serialize(final Date v, final JsonGenerator g, final SerializerProvider p) throws IOException {
                g.writeString(INSTANT.format(v.toInstant()));
            }
        });
        times.addDeserializer(Date.class, new JsonDeserializer<>() {
            @Override
            public Date deserialize(final JsonParser p, final DeserializationContext c) throws IOException {
                return Date.from(Instant.parse(p.getValueAsString()));
            }
        });

        mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(times)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .visibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
                .visibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
                .visibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.NONE)
                .visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                // Jackson annotations on the models shape the REST API, not storage, so they are
                // ignored here; only Spring Data's @Transient keeps a field out, as with MongoDB.
                .annotationIntrospector(new NopAnnotationIntrospector() {
                    @Override
                    public boolean hasIgnoreMarker(final AnnotatedMember m) {
                        return m.hasAnnotation(org.springframework.data.annotation.Transient.class);
                    }
                })
                .build();
    }

    String write(final Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // The message can quote field values, so only the type is reported.
            throw new IllegalStateException("Could not serialize " + value.getClass().getSimpleName());
        }
    }

    <T> T read(final String json, final Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not deserialize " + type.getSimpleName());
        }
    }

    /**
     * The value to bind when comparing against {@code json_extract}, which returns JSON
     * booleans as 1 or 0 and timestamps as the strings written above.
     */
    Object toSql(final Object value) {
        if (value == null || value instanceof String || value instanceof Number) {
            return value;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof Instant i) {
            return INSTANT.format(i);
        }
        if (value instanceof LocalDateTime t) {
            return LOCAL_DATE_TIME.format(t);
        }
        if (value instanceof Date d) {
            return INSTANT.format(d.toInstant());
        }
        if (value instanceof LocalDate d) {
            return d.toString();
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Character c) {
            return c.toString();
        }
        throw new IllegalArgumentException("Cannot compare a stored field with a " + value.getClass().getSimpleName());
    }
}
