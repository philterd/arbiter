package ai.philterd.arbiter.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class PiiWeights {

    public static final int DEFAULT_FALLBACK = 1;

    private static final Map<String, Integer> DEFAULTS;
    static {
        Map<String, Integer> m = new LinkedHashMap<>();
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

    public static int weightFor(String type, Map<String, Integer> overrides) {
        if (type == null) return DEFAULT_FALLBACK;
        String key = type.trim().toLowerCase(Locale.ROOT);
        if (overrides != null) {
            Integer override = overrides.get(key);
            if (override != null) return Math.max(0, override);
        }
        Integer def = DEFAULTS.get(key);
        return def == null ? DEFAULT_FALLBACK : def;
    }

    public static Map<String, Integer> effective(Map<String, Integer> overrides) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String type : PiiTypes.values()) {
            out.put(type, weightFor(type, overrides));
        }
        return out;
    }

    private PiiWeights() {}
}
