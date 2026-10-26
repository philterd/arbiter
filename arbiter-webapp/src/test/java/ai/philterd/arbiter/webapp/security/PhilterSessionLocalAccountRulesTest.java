/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.security;

import ai.philterd.arbiter.model.GeneralSettings;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.UserRepository;
import ai.philterd.arbiter.service.GeneralSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Arbiter's local-account rules do not apply to a person signed in through Philter, and the reverse. */
class PhilterSessionLocalAccountRulesTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static User shadow(final boolean mustChangePassword) {
        final User user = new User();
        user.setEmail("jdoe");
        user.setRoles(Set.of("USER"));
        user.setMustChangePassword(mustChangePassword);
        return user;
    }

    @Test
    void localMfaAndPasswordGatesLetAPhilterSessionThrough() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new PhilterSessionAuthentication("jdoe", "enc",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        final UserRepository users = mock(UserRepository.class);
        when(users.findByEmail("jdoe")).thenReturn(Optional.of(shadow(true)));
        final GeneralSettingsService settings = mock(GeneralSettingsService.class);
        final GeneralSettings requireMfa = new GeneralSettings();
        requireMfa.setRequireMfa(true);
        when(settings.load()).thenReturn(requireMfa);
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/queue");

        assertTrue(new MfaEnrollmentInterceptor(settings, users)
                .preHandle(request, new MockHttpServletResponse(), new Object()));
        assertTrue(new PasswordChangeRequiredInterceptor(users)
                .preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void localSignInRefusesARowWithNoPassword() {
        final UserRepository users = mock(UserRepository.class);
        when(users.findByEmail("jdoe")).thenReturn(Optional.of(shadow(false)));

        assertThrows(UsernameNotFoundException.class, () -> new MongoUserDetailsService(users).loadUserByUsername("jdoe"));
    }
}
