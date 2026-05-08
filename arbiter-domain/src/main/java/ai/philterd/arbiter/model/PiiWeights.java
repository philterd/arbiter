/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class PiiWeights {

    public static final int DEFAULT_FALLBACK = 1;

    private static final Map<String, Integer> DEFAULTS;
    static {
        final Map<String, Integer> m = new LinkedHashMap<>();
        m.put("ssn", 10);
        m.put("credit-card", 10);
        m.put("phone-number", 5);
        m.put("email-address", 5);
        m.put("person", 3);
        m.put("first-name", 3);
        m.put("surname", 3);
        m.put("physician-name", 3);
        m.put("street-address", 3);
        m.put("zip-code", 2);
        DEFAULTS = Collections.unmodifiableMap(m);
    }

    public static Map<String, Integer> defaults() {
        return DEFAULTS;
    }

    public static int weightFor(final String type, final Map<String, Integer> overrides) {
        if (type == null) return DEFAULT_FALLBACK;
        final String key = type.trim().toLowerCase(Locale.ROOT);
        if (overrides != null) {
            final Integer override = overrides.get(key);
            if (override != null) return Math.max(0, override);
        }
        final Integer def = DEFAULTS.get(key);
        return def == null ? DEFAULT_FALLBACK : def;
    }

    public static Map<String, Integer> effective(final Map<String, Integer> overrides) {
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (String type : PiiTypes.values()) {
            out.put(type, weightFor(type, overrides));
        }
        return out;
    }

    private PiiWeights() {}
}
