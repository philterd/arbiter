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

import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.workspace.util.Hashing;
import ai.philterd.philter.model.CurrentUser;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a Philter API key presented to Philter Workspace's API to the Philter user that owns it, with
 * {@code GET /api/users/me}, so the key must hold {@code users:read}. The answer is cached for
 * {@code philter-workspace.api-keys.cache-seconds} (default 30), keyed by a hash of the key, so a burst of calls
 * makes one Philter call and a revoked key stops working within that time. The key itself is never
 * stored or logged. Refusals are not cached, so a key that starts working does so at once.
 */
@Service
public class PhilterApiKeys {

    private static final Logger log = LoggerFactory.getLogger(PhilterApiKeys.class);

    /** Bounds the cache; past this many keys it starts again empty. */
    static final int MAX_CACHED = 10_000;

    private final PhilterSignInService signInService;
    private final PhilterUserProvisioner provisioner;
    private final Clock clock;
    private final Duration ttl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public PhilterApiKeys(final PhilterSignInService signInService,
                          final PhilterUserProvisioner provisioner,
                          @Value("${philter-workspace.api-keys.cache-seconds:30}") final long cacheSeconds) {
        this(signInService, provisioner, Clock.systemUTC(), Duration.ofSeconds(cacheSeconds));
    }

    PhilterApiKeys(final PhilterSignInService signInService, final PhilterUserProvisioner provisioner,
                   final Clock clock, final Duration ttl) {
        if (ttl.isNegative()) {
            throw new IllegalStateException("philter-workspace.api-keys.cache-seconds must be zero or more.");
        }
        this.signInService = signInService;
        this.provisioner = provisioner;
        this.clock = clock;
        this.ttl = ttl;
    }

    /** The caller a key resolves to: their Philter username and Philter Workspace role. */
    public record Caller(String username, String role) {
    }

    /** Why a key was not accepted. */
    public enum Refusal {
        /** Philter does not accept the key, or its user is deactivated. */
        INVALID,
        /** Philter accepts the key, but it does not hold {@code users:read}. */
        MISSING_SCOPE,
        /** Philter could not be asked. */
        UNAVAILABLE
    }

    /** Thrown when a key is not accepted. */
    public static class RefusedException extends Exception {
        private final Refusal refusal;

        public RefusedException(final Refusal refusal) {
            super(refusal.name());
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    public Caller resolve(final String apiKey) throws RefusedException {
        final String hash = Hashing.sha512Hex(apiKey);
        final long now = clock.millis();
        final Cached cached = cache.get(hash);
        if (cached != null && cached.expiresAt() > now) {
            return cached.caller();
        }
        final CurrentUser me;
        try {
            me = signInService.currentUserForApiKey(apiKey);
        } catch (UnauthorizedException e) {
            cache.remove(hash);
            throw new RefusedException(Refusal.INVALID);
        } catch (ClientException e) {
            // A rate limit or a server error (such as a proxy in front of Philter) says nothing about the key.
            if (e.getStatusCode() == 429 || e.getStatusCode() >= 500 || e.getStatusCode() == 0) {
                log.warn("Could not check an API key with Philter: {}", PhilterClients.describe(e));
                throw new RefusedException(Refusal.UNAVAILABLE);
            }
            cache.remove(hash);
            throw new RefusedException(e.getStatusCode() == 403 ? Refusal.MISSING_SCOPE : Refusal.INVALID);
        } catch (Exception e) {
            log.warn("Could not check an API key with Philter: {}", PhilterClients.describe(e));
            throw new RefusedException(Refusal.UNAVAILABLE);
        }
        if (me == null || !me.isActive() || me.getUsername() == null) {
            cache.remove(hash);
            throw new RefusedException(Refusal.INVALID);
        }
        final Caller caller = new Caller(me.getUsername(), provisioner.provision(me.getUsername(), me.getRole()));
        if (!ttl.isZero()) {
            if (cache.size() >= MAX_CACHED) {
                cache.clear();
            }
            cache.put(hash, new Cached(caller, now + ttl.toMillis()));
        }
        return caller;
    }

    private record Cached(Caller caller, long expiresAt) {
    }
}
