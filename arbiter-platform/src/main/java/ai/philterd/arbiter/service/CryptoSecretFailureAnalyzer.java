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

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Replaces the default Spring Boot stack-trace dump with a clean banner when Arbiter
 * fails to start because {@code arbiter.crypto.secret} is missing or malformed. The
 * analyzer is registered via
 * {@code META-INF/spring/org.springframework.boot.diagnostics.FailureAnalyzer.imports}
 * and matches on {@link CryptoSecretConfigurationException} regardless of where in the
 * cause chain the exception lands.
 *
 * <p>Spring Boot prints the {@code Description} and {@code Action} returned here under
 * its standard "APPLICATION FAILED TO START" banner, demotes the underlying exception
 * to debug log level, and exits with a non-zero status.
 */
public class CryptoSecretFailureAnalyzer
        extends AbstractFailureAnalyzer<CryptoSecretConfigurationException> {

    @Override
    protected FailureAnalysis analyze(final Throwable rootFailure,
                                      final CryptoSecretConfigurationException cause) {
        return new FailureAnalysis(description(cause), ACTION, cause);
    }

    private static String description(final CryptoSecretConfigurationException cause) {
        return switch (cause.getReason()) {
            case UNSET -> """
                    The arbiter.crypto.secret property is not set.

                    Arbiter uses this value as the AES-256 key that protects stored credentials \
                    (Philter API keys, data-source passwords) and as the HMAC key for API key \
                    hashing. Starting without it would leave both at-rest secrets and bearer-token \
                    verification unprotected, so Arbiter refuses to come up.""";
            case PLACEHOLDER -> """
                    The arbiter.crypto.secret property is still set to the placeholder value from \
                    .env.example ("replace-me-with-base64-of-32-random-bytes").

                    That placeholder is a public string committed to the repository — using it as \
                    the encryption key would mean every running Arbiter instance shares the same \
                    well-known key. The application refuses to start until a real key is supplied.""";
            case NOT_BASE64 -> """
                    The arbiter.crypto.secret property is set, but its value is not valid base64 \
                    under the standard alphabet (A-Z a-z 0-9 + / =).

                    Common causes: a passphrase typed in directly instead of base64 of random bytes; \
                    a Base64URL value (which uses '-' and '_' instead of '+' and '/'); or stray \
                    whitespace, quotes, or line breaks copied into the value.""";
            case WRONG_LENGTH -> """
                    The arbiter.crypto.secret property decoded successfully but to %d bytes — \
                    Arbiter requires exactly %d bytes (AES-256 / HMAC-SHA-256 key).

                    Most often this means the configured value is base64 of a passphrase or of a \
                    truncated random buffer rather than 32 bytes of random material.""".formatted(
                            cause.getDecodedLength(), CryptoSecretLoader.REQUIRED_BYTES);
        };
    }

    private static final String ACTION = """
            Generate a real key and place it in your .env file (next to docker-compose.yaml):

                echo "ARBITER_CRYPTO_SECRET=$(openssl rand -base64 32)" > .env

            Then start Arbiter again. The value must be base64 of exactly 32 random bytes; the \
            command above produces one in the right shape. If you are not running under \
            docker-compose, set the same key as an environment variable or in a Spring \
            properties file Arbiter loads at startup.""";
}
