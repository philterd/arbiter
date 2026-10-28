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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

/**
 * Whether to warn an administrator that Philter's bootstrap API key is still active. The answer is the
 * same for every administrator, so it is cached for a minute rather than asked of Philter on each page.
 * When Philter cannot be asked, no warning is shown, and that is cached too, so a Philter that is down
 * does not hold up each visit to the dashboard.
 */
@Service
public class BootstrapKeyWarning {

    /** Philter's docs on retiring the bootstrap key: create a replacement key, then revoke it. */
    public static final String DOCS_URL = "https://philterd.github.io/philter/latest/account/api_keys.html"
            + "#bootstrapping-an-api-key-for-automation";

    private static final Logger log = LoggerFactory.getLogger(BootstrapKeyWarning.class);
    private static final Duration TTL = Duration.ofMinutes(1);

    private final PhilterSignInService signInService;
    private final Clock clock;
    private volatile Boolean active;
    private volatile long checkedAt;

    @Autowired
    public BootstrapKeyWarning(final PhilterSignInService signInService) {
        this(signInService, Clock.systemUTC());
    }

    BootstrapKeyWarning(final PhilterSignInService signInService, final Clock clock) {
        this.signInService = signInService;
        this.clock = clock;
    }

    /** True when the caller is an administrator signed in through Philter and the bootstrap key is active. */
    public boolean show(final Authentication authentication) {
        if (!(authentication instanceof PhilterSessionAuthentication session)
                || session.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            return false;
        }
        final long now = clock.millis();
        final Boolean cached = active;
        if (cached != null && now - checkedAt < TTL.toMillis()) {
            return cached;
        }
        boolean answer;
        try {
            answer = signInService.bootstrapKeyActive(session.getEncryptedSessionKey());
        } catch (Exception e) {
            log.warn("Could not check Philter for its bootstrap API key: {}", PhilterClients.describe(e));
            answer = false;
        }
        checkedAt = now;
        active = answer;
        return answer;
    }
}
