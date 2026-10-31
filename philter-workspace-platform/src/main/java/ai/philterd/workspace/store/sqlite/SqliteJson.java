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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.MapperConfig;
import tools.jackson.databind.introspect.AnnotatedMember;
import tools.jackson.databind.introspect.NopAnnotationIntrospector;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

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
        final SimpleModule times = new SimpleModule("philter-workspace-sqlite-times");
        times.addSerializer(Instant.class, new ValueSerializer<>() {
            @Override
            public void serialize(final Instant v, final JsonGenerator g, final SerializationContext p) {
                g.writeString(INSTANT.format(v));
            }
        });
        times.addDeserializer(Instant.class, new ValueDeserializer<>() {
            @Override
            public Instant deserialize(final JsonParser p, final DeserializationContext c) {
                return Instant.parse(p.getValueAsString());
            }
        });
        times.addSerializer(LocalDateTime.class, new ValueSerializer<>() {
            @Override
            public void serialize(final LocalDateTime v, final JsonGenerator g, final SerializationContext p) {
                g.writeString(LOCAL_DATE_TIME.format(v));
            }
        });
        times.addDeserializer(LocalDateTime.class, new ValueDeserializer<>() {
            @Override
            public LocalDateTime deserialize(final JsonParser p, final DeserializationContext c) {
                return LocalDateTime.parse(p.getValueAsString());
            }
        });
        times.addSerializer(Date.class, new ValueSerializer<>() {
            @Override
            public void serialize(final Date v, final JsonGenerator g, final SerializationContext p) {
                g.writeString(INSTANT.format(v.toInstant()));
            }
        });
        times.addDeserializer(Date.class, new ValueDeserializer<>() {
            @Override
            public Date deserialize(final JsonParser p, final DeserializationContext c) {
                return Date.from(Instant.parse(p.getValueAsString()));
            }
        });

        // Jackson 2's defaults, so stored records keep the form they were written in before Jackson 3.
        mapper = JsonMapper.builderWithJackson2Defaults()
                .addModule(times)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .changeDefaultVisibility(v -> v
                        .withVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
                        .withVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
                        .withVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.NONE)
                        .withVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY))
                // Jackson annotations on the models shape the REST API, not storage, so they are
                // ignored here; only Spring Data's @Transient keeps a field out, as with MongoDB.
                .annotationIntrospector(new NopAnnotationIntrospector() {
                    @Override
                    public boolean hasIgnoreMarker(final MapperConfig<?> config, final AnnotatedMember m) {
                        return m.hasAnnotation(org.springframework.data.annotation.Transient.class);
                    }
                })
                .build();
    }

    String write(final Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            // The message can quote field values, so only the type is reported.
            throw new IllegalStateException("Could not serialize " + value.getClass().getSimpleName());
        }
    }

    <T> T read(final String json, final Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException e) {
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
