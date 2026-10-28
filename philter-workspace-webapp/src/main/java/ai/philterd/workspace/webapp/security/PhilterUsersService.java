/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.security;

import ai.philterd.philter.model.CreateUserRequest;
import ai.philterd.philter.model.GetUsersResponse;
import ai.philterd.philter.model.ListOptions;
import ai.philterd.philter.model.User;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Philter's Users API, called with the signed-in administrator's session key, so Philter checks that
 * they are an administrator and its audit log names them. Passwords are passed straight through and
 * never kept.
 */
@Service
public class PhilterUsersService {

    /** Philter caps a page of users at 100. */
    static final int PAGE_SIZE = 100;

    private final PhilterSignInService signInService;

    public PhilterUsersService(final PhilterSignInService signInService) {
        this.signInService = signInService;
    }

    /** Every user, active and deactivated, sorted by username. */
    public List<User> listUsers(final String encryptedKey) throws IOException {
        final List<User> users = new ArrayList<>();
        int offset = 0;
        while (true) {
            final GetUsersResponse page = signInService.client(encryptedKey)
                    .getUsers(new ListOptions().offset(offset).limit(PAGE_SIZE));
            final List<User> batch = page == null || page.getUsers() == null ? List.of() : page.getUsers();
            users.addAll(batch);
            offset += batch.size();
            if (batch.isEmpty() || offset >= page.getTotal()) {
                return users;
            }
        }
    }

    /** One page of users, as {@code options} asks: offset, limit, sort, search, and role and active filters. */
    public GetUsersResponse listPage(final String encryptedKey, final ListOptions options) throws IOException {
        return signInService.client(encryptedKey).getUsers(options);
    }

    public User getUser(final String encryptedKey, final String username) throws IOException {
        return signInService.client(encryptedKey).getUser(username);
    }

    /** Creates a user who signs in with {@code password}, which they must change at first sign-in. */
    public void createUser(final String encryptedKey, final String username, final String email,
                           final String role, final String password) throws IOException {
        final CreateUserRequest request = new CreateUserRequest();
        request.setUsername(username);
        request.setEmail(email == null || email.isBlank() ? null : email);
        request.setRole(role);
        request.setPassword(password);
        signInService.client(encryptedKey).createUser(request);
    }

    public void setRole(final String encryptedKey, final String username, final String role) throws IOException {
        signInService.client(encryptedKey).setUserRole(username, role);
    }

    public void setEmail(final String encryptedKey, final String username, final String email) throws IOException {
        signInService.client(encryptedKey).setUserEmail(username, email);
    }

    /** Resets the password; the person must change it at next sign-in, and their sessions end. */
    public void resetPassword(final String encryptedKey, final String username, final String password)
            throws IOException {
        signInService.client(encryptedKey).setPassword(username, password);
    }

    public void deactivate(final String encryptedKey, final String username) throws IOException {
        signInService.client(encryptedKey).deactivateUser(username);
    }

    public void reactivate(final String encryptedKey, final String username) throws IOException {
        signInService.client(encryptedKey).reactivateUser(username);
    }

    public void unlockSignIn(final String encryptedKey, final String username) throws IOException {
        signInService.client(encryptedKey).unlockUserSignIn(username);
    }

    public void unlockMfa(final String encryptedKey, final String username) throws IOException {
        signInService.client(encryptedKey).unlockUserMfa(username);
    }

    public void removeMfa(final String encryptedKey, final String username) throws IOException {
        signInService.client(encryptedKey).removeUserMfa(username);
    }

    /** Signs the person out everywhere. */
    public int revokeSessions(final String encryptedKey, final String username) throws IOException {
        return signInService.client(encryptedKey).revokeSessionKeys(username);
    }
}
