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

import ai.philterd.arbiter.philter.PhilterClients;
import ai.philterd.arbiter.philter.PhilterTlsConfig;
import ai.philterd.arbiter.service.PhilterSettings;
import ai.philterd.arbiter.service.SymmetricCipher;
import ai.philterd.philter.PhilterClient;
import ai.philterd.philter.model.ApiKey;
import ai.philterd.philter.model.CurrentUser;
import ai.philterd.philter.model.GetApiKeysResponse;
import ai.philterd.philter.model.MfaEnrollment;
import ai.philterd.philter.model.SignInResponse;
import ai.philterd.philter.model.StatusResponse;
import ai.philterd.philter.model.exceptions.ClientException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.List;

/**
 * The calls Arbiter makes to the Philter that signs people in ({@code ARBITER_PHILTER_URL}), with
 * the global {@code arbiter.philter.tls.*} settings. Passwords and codes are passed straight through and
 * never kept. Session keys and MFA challenges leave this class only encrypted.
 */
@Service
public class PhilterSignInService {

    /** The Philter user that {@code PHILTER_BOOTSTRAP_API_KEY} is seeded on. */
    static final String BOOTSTRAP_KEY_OWNER = "admin";
    private static final int KEY_PAGE = 100;
    /** For the advisory checks on the sign-in page and dashboard, which should not hold a page up. */
    private static final long SHORT_TIMEOUT_SECONDS = 5;

    private final PhilterSettings settings;
    private final PhilterTlsConfig tls;
    private final SymmetricCipher cipher;

    public PhilterSignInService(final PhilterSettings settings,
                                final PhilterTlsConfig tls,
                                final SymmetricCipher cipher) {
        this.settings = settings;
        this.tls = tls;
        this.cipher = cipher;
    }

    public SignInResponse signIn(final String username, final String password,
                                 final String clientAddress, final String userAgent) throws IOException {
        return client(null).signIn(username, password, clientAddress, userAgent);
    }

    public SignInResponse completeSignIn(final String encryptedChallenge, final String code,
                                         final String clientAddress, final String userAgent) throws IOException {
        return client(null).completeSignIn(cipher.decrypt(encryptedChallenge), code, clientAddress, userAgent);
    }

    public CurrentUser currentUser(final String encryptedKey) throws IOException {
        return client(encryptedKey).getCurrentUser();
    }

    /** Revokes the session key (sign-out). */
    public void signOut(final String encryptedKey) throws IOException {
        client(encryptedKey).signOut();
    }

    /** Changes the key's own user's password. Philter then revokes that user's session keys. */
    public void changePassword(final String encryptedKey, final String currentPassword,
                               final String newPassword) throws IOException {
        client(encryptedKey).changePassword(currentPassword, newPassword);
    }

    public MfaEnrollment startMfaEnrollment(final String encryptedKey, final String currentPassword) throws IOException {
        return client(encryptedKey).startMfaEnrollment(currentPassword);
    }

    /** Confirms MFA enrollment. Philter then revokes the user's session keys. */
    public void confirmMfaEnrollment(final String encryptedKey, final String code) throws IOException {
        client(encryptedKey).confirmMfaEnrollment(code);
    }

    /**
     * Philter's unauthenticated health, which carries its version.
     */
    public StatusResponse health() throws IOException {
        return PhilterClients.build(settings.url(), null, tls.sslContext(), null, SHORT_TIMEOUT_SECONDS).health();
    }

    /**
     * Whether the bootstrap key from {@code PHILTER_BOOTSTRAP_API_KEY} is still active. Philter seeds it on
     * its {@code admin} user, so any administrator's session key can list it; a Philter with no
     * {@code admin} user (404) has none.
     */
    public boolean bootstrapKeyActive(final String encryptedKey) throws IOException {
        final PhilterClient client = PhilterClients.build(settings.url(), cipher.decrypt(encryptedKey),
                tls.sslContext(), PhilterSignInService::currentClientAddress, SHORT_TIMEOUT_SECONDS);
        int offset = 0;
        while (true) {
            final GetApiKeysResponse page;
            try {
                page = client.getApiKeys(BOOTSTRAP_KEY_OWNER, offset, KEY_PAGE, false);
            } catch (ClientException e) {
                if (e.getStatusCode() == 404) {
                    return false;
                }
                throw e;
            }
            final List<ApiKey> keys = page == null || page.getApiKeys() == null ? List.of() : page.getApiKeys();
            if (keys.stream().anyMatch(ApiKey::isBootstrap)) {
                return true;
            }
            offset += keys.size();
            if (keys.isEmpty() || offset >= page.getTotal()) {
                return false;
            }
        }
    }

    public String encrypt(final String secret) {
        return cipher.encrypt(secret);
    }

    /** The user that owns a Philter API key presented to Arbiter's API. Needs the key to hold {@code users:read}. */
    public CurrentUser currentUserForApiKey(final String apiKey) throws IOException {
        return PhilterClients.build(settings.url(), apiKey, tls.sslContext(),
                PhilterSignInService::currentClientAddress).getCurrentUser();
    }

    /**
     * A client that sends the address of the person whose request is being served, so Philter's audit
     * log records it for every call made on their behalf, not only sign-in.
     */
    PhilterClient client(final String encryptedKey) {
        final String key = encryptedKey == null ? null : cipher.decrypt(encryptedKey);
        return PhilterClients.build(settings.url(), key, tls.sslContext(),
                PhilterSignInService::currentClientAddress);
    }

    private static String currentClientAddress() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? PhilterSignInFlow.clientAddress(attributes.getRequest()) : null;
    }
}
