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

import ai.philterd.arbiter.model.Roles;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultUserLoader}. Finding #7 changed the loader from "fall
 * back to a generated password printed on stdout" to "fail-fast on missing
 * config", matching the existing pattern for {@code arbiter.crypto.secret}.
 * These tests pin the new contract so a future refactor can't re-introduce the
 * stdout-leak path.
 */
class DefaultUserLoaderTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private InboxService inboxService;
    private ApplicationArguments args;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        inboxService = mock(InboxService.class);
        args = mock(ApplicationArguments.class);
        // Default: fresh DB with no admin.
        when(userRepository.countByRolesContaining(Roles.ADMIN)).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
    }

    /** Spring would inject the password via @Value(arbiter.admin.initial-password). In the
     *  unit test we pass it directly so each scenario controls the bound value. */
    private DefaultUserLoader newLoader(final String initialPassword) {
        return new DefaultUserLoader(userRepository, passwordEncoder, inboxService, initialPassword);
    }

    @Test
    void refusesToStartWhenInitialPasswordIsUnset() {
        // Pre-fix this generated a random password and printed it to System.out
        // — the credential then lived in journalctl / pod logs / log aggregators
        // forever. Now: fail-fast, matching the arbiter.crypto.secret pattern.
        // CLAUDE.md explicitly bans writes of sensitive data to System.out.
        // null and "" both represent "unset" — Spring's @Value default is "" when the
        // property is missing.
        final IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> newLoader("").run(args));

        assertTrue(ex.getMessage().contains(DefaultUserLoader.INITIAL_PASSWORD_ENV_VAR),
                "error must name the env var so the operator knows what to set: " + ex.getMessage());
        verify(userRepository, never()).save(any());
        verify(inboxService, never()).sendHtml(anyString(), anyString());
    }

    @Test
    void refusesToStartWhenInitialPasswordIsBlank() {
        final IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> newLoader("   ").run(args));

        assertTrue(ex.getMessage().contains(DefaultUserLoader.INITIAL_PASSWORD_ENV_VAR));
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesToStartWhenInitialPasswordIsTooShort() {
        // Length policy matches the /settings/password and Admin → Users rules.
        final IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> newLoader("tooshort").run(args));

        assertTrue(ex.getMessage().toLowerCase().contains("minimum")
                        || ex.getMessage().toLowerCase().contains("shorter"),
                "error should explain the length problem: " + ex.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    void seedsAdminWhenInitialPasswordIsConfigured() {
        // Happy path: password is set, meets the length policy, no admin exists.
        // The admin row is created with the supplied password.
        newLoader("ChangeMeOnFirstLogin!").run(args);

        final ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        final User saved = userCaptor.getValue();
        assertEquals("admin@philterd.ai", saved.getEmail());
        assertTrue(saved.getRoles().contains(Roles.ADMIN));
        // The configured-password flow does NOT force a rotation — the operator
        // controls where the credential lives (compose / secret store) and
        // forcing a rotation would defeat the point of configuring it.
        assertFalse(saved.isMustChangePassword(),
                "configured-password admin must not be flagged for rotation");
        // PasswordEncoder is called with the configured value (not a generated one).
        verify(passwordEncoder).encode("ChangeMeOnFirstLogin!");
        verify(inboxService).sendHtml(anyString(), anyString());
    }

    @Test
    void doesNothingWhenAdminAlreadyExists() {
        // On a subsequent boot — admin already present — the loader is a no-op
        // regardless of whether the password is set. This guarantees the
        // password requirement only applies on first install.
        when(userRepository.countByRolesContaining(Roles.ADMIN)).thenReturn(1L);

        // Even with an unset password, the bean must construct and run without
        // throwing — otherwise an upgrade would fail-fast on an unrelated config.
        newLoader("").run(args);

        verify(userRepository, never()).save(any());
        verify(inboxService, never()).sendHtml(anyString(), anyString());
    }
}
