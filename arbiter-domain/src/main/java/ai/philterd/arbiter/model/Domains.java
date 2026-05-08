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
