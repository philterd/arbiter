package ai.philterd.arbiter.model;

import java.util.List;

/** Curated list of PII-heavy domains that a batch can be tagged with. */
public final class Domains {

    public static final List<String> VALUES = List.of(
            "Financial",
            "Legal",
            "Healthcare",
            "Education",
            "Technology",
            "Government",
            "Insurance",
            "Human Resources",
            "Retail",
            "Telecommunications",
            "Marketing",
            "Other"
    );

    public static boolean isValid(final String value) {
        return value != null && VALUES.contains(value);
    }

    private Domains() {
    }
}
