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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the Arbiter user row for a person who signs in through Philter. The row is keyed by Philter
 * username, held in the {@code email} field, so group membership, team leads, and the user recorded on
 * batches, documents, and the audit log work. It has no password, MFA secret, or API key: Philter
 * holds those.
 *
 * <p>A Philter {@code admin} is an Arbiter {@code ADMIN}. A Philter {@code user} is an Arbiter
 * {@code USER}, or {@code AUDITOR} when an Arbiter administrator has set that. The role is set again at
 * every sign-in, and a session ends at its next check when the role has changed
 * ({@link PhilterSessionValidationFilter}).</p>
 */
@Service
public class PhilterUserProvisioner {

    static final String PHILTER_ADMIN_ROLE = "admin";

    private final UserRepository userRepository;

    public PhilterUserProvisioner(final UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Creates or updates the row for {@code username} and returns its role. */
    public String provision(final String username, final String philterRole) {
        final User existing = userRepository.findByEmail(username).orElse(null);
        final User user = existing != null ? existing : new User();
        if (existing == null) {
            user.setId(UUID.randomUUID().toString());
            user.setEmail(username);
            user.setCreatedAt(LocalDateTime.now());
        }
        final String role = roleFor(philterRole, user.getRoles());
        if (existing == null || !Set.of(role).equals(user.getRoles())) {
            user.setRoles(Set.of(role));
            userRepository.save(user);
        }
        return role;
    }

    /** After a role change in Philter, updates the person's record if they have one; creates none. */
    public void applyPhilterRole(final String username, final String philterRole) {
        userRepository.findByEmail(username).ifPresent(user -> {
            final String role = roleFor(philterRole, user.getRoles());
            if (!Set.of(role).equals(user.getRoles())) {
                user.setRoles(Set.of(role));
                userRepository.save(user);
            }
        });
    }

    /**
     * Sets the Arbiter role of a Philter {@code user}, creating their record if they have none, and
     * returns the record. A Philter {@code admin} stays an Arbiter {@code ADMIN} whatever is asked.
     */
    public User setArbiterRole(final String username, final String philterRole, final boolean auditor) {
        final User user = userRepository.findByEmail(username).orElseGet(() -> {
            final User created = new User();
            created.setId(UUID.randomUUID().toString());
            created.setEmail(username);
            created.setCreatedAt(LocalDateTime.now());
            return created;
        });
        final String role = PHILTER_ADMIN_ROLE.equalsIgnoreCase(philterRole) ? Roles.ADMIN
                : auditor ? Roles.AUDITOR : Roles.USER;
        user.setRoles(Set.of(role));
        return userRepository.save(user);
    }

    static String roleFor(final String philterRole, final Set<String> current) {
        if (PHILTER_ADMIN_ROLE.equalsIgnoreCase(philterRole)) {
            return Roles.ADMIN;
        }
        return current != null && current.contains(Roles.AUDITOR) ? Roles.AUDITOR : Roles.USER;
    }
}
