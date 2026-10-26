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

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * A person signed in through Philter. The name is their Philter username. Their Philter session key is
 * held encrypted with Arbiter's crypto secret, and only in the server-side session, never in the browser.
 */
public class PhilterSessionAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final String username;
    private final String encryptedSessionKey;

    public PhilterSessionAuthentication(final String username,
                                        final String encryptedSessionKey,
                                        final Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.username = username;
        this.encryptedSessionKey = encryptedSessionKey;
        setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return username;
    }

    /** No password is kept. */
    @Override
    public Object getCredentials() {
        return null;
    }

    public String getEncryptedSessionKey() {
        return encryptedSessionKey;
    }

    /** Leaves out the username and key, since Spring logs authentications at debug. */
    @Override
    public String toString() {
        return "PhilterSessionAuthentication";
    }
}
