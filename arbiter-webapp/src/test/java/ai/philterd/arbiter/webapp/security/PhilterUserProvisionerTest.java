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

import ai.philterd.arbiter.model.Roles;
import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhilterUserProvisionerTest {

    private UserRepository userRepository;
    private PhilterUserProvisioner provisioner;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        provisioner = new PhilterUserProvisioner(userRepository);
    }

    private static User existing(final String role) {
        final User user = new User();
        user.setId("u1");
        user.setEmail("jdoe");
        user.setRoles(Set.of(role));
        return user;
    }

    @Test
    void firstSignInCreatesARowWithNoPassword() {
        when(userRepository.findByEmail("jdoe")).thenReturn(Optional.empty());

        assertEquals(Roles.USER, provisioner.provision("jdoe", "user"));

        final ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertEquals("jdoe", saved.getValue().getEmail());
        assertNotNull(saved.getValue().getId());
        assertNull(saved.getValue().getPasswordHash());
        assertEquals(Set.of(Roles.USER), saved.getValue().getRoles());
    }

    @Test
    void philterAdminIsArbiterAdmin() {
        when(userRepository.findByEmail("jdoe")).thenReturn(Optional.of(existing(Roles.USER)));

        assertEquals(Roles.ADMIN, provisioner.provision("jdoe", "admin"));
        verify(userRepository).save(any());
    }

    @Test
    void auditorSetInArbiterIsKeptForAPhilterUser() {
        when(userRepository.findByEmail("jdoe")).thenReturn(Optional.of(existing(Roles.AUDITOR)));

        assertEquals(Roles.AUDITOR, provisioner.provision("jdoe", "user"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void adminDemotedInPhilterIsDemotedAtNextSignIn() {
        when(userRepository.findByEmail("jdoe")).thenReturn(Optional.of(existing(Roles.ADMIN)));

        assertEquals(Roles.USER, provisioner.provision("jdoe", "user"));
        verify(userRepository).save(any());
    }

    @Test
    void anApiKeyOnTheRecordIsCleared() {
        final User user = existing(Roles.USER);
        user.setApiKey("hash-of-an-old-key");
        when(userRepository.findByEmail("jdoe")).thenReturn(Optional.of(user));

        provisioner.provision("jdoe", "user");

        assertNull(user.getApiKey());
        verify(userRepository).save(user);
    }

    @Test
    void roleChangeInPhilterUpdatesOnlyAnExistingRecord() {
        when(userRepository.findByEmail("jdoe")).thenReturn(Optional.empty());
        provisioner.applyPhilterRole("jdoe", "admin");
        verify(userRepository, never()).save(any());

        final User user = existing(Roles.AUDITOR);
        when(userRepository.findByEmail("jdoe")).thenReturn(Optional.of(user));
        provisioner.applyPhilterRole("jdoe", "admin");
        assertEquals(Set.of(Roles.ADMIN), user.getRoles());
    }

    @Test
    void arbiterRoleOfAPhilterAdminStaysAdmin() {
        when(userRepository.findByEmail("jdoe")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertEquals(Set.of(Roles.AUDITOR), provisioner.setArbiterRole("jdoe", "user", true).getRoles());
        assertEquals(Set.of(Roles.ADMIN), provisioner.setArbiterRole("jdoe", "admin", true).getRoles());
    }
}
