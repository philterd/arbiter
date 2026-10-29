/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.ui;

import java.util.List;

/**
 * Philter's rule for the names it uses in request paths, such as a custom list's, a context's, or a hold's reference
 * ({@code PathSafeNames} in Philter), checked here so a form can say what is wrong before Philter refuses it.
 */
public final class PhilterNames {

    private static final List<String> FORBIDDEN = List.of("/", "\\", ";", "%");

    private PhilterNames() {
    }

    /**
     * What is wrong with {@code name} (already trimmed) as {@code label}, such as "list name" or "hold reference",
     * or null.
     */
    public static String problem(final String name, final String label) {
        if (name == null || name.isEmpty()) {
            return "Enter a " + label.substring(label.lastIndexOf(' ') + 1) + ".";
        }
        if (".".equals(name) || "..".equals(name) || FORBIDDEN.stream().anyMatch(name::contains)
                || name.chars().anyMatch(Character::isISOControl)) {
            return "A " + label + " cannot contain /, \\, ;, or %, and cannot be . or ..";
        }
        return null;
    }
}
