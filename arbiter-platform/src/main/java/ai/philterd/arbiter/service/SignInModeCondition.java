/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Locale;

/**
 * Registers a bean only in one sign-in mode, reading {@code arbiter.sign-in.mode} as {@link SignInSettings}
 * does (trimmed, any case, blank meaning {@code local}), so beans and settings never disagree on the mode.
 */
public abstract class SignInModeCondition implements Condition {

    private final String mode;

    protected SignInModeCondition(final String mode) {
        this.mode = mode;
    }

    @Override
    public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
        return mode.equals(normalize(context.getEnvironment().getProperty("arbiter.sign-in.mode")));
    }

    static String normalize(final String value) {
        return value == null || value.isBlank() ? SignInSettings.MODE_LOCAL : value.trim().toLowerCase(Locale.ROOT);
    }

    public static class Local extends SignInModeCondition {
        public Local() {
            super(SignInSettings.MODE_LOCAL);
        }
    }

    public static class Philter extends SignInModeCondition {
        public Philter() {
            super(SignInSettings.MODE_PHILTER);
        }
    }
}
