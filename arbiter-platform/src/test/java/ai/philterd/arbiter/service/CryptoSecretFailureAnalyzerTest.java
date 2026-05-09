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

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoSecretFailureAnalyzerTest {

    private final CryptoSecretFailureAnalyzer analyzer = new CryptoSecretFailureAnalyzer();

    private FailureAnalysis analyze(final CryptoSecretConfigurationException e) {
        // The public analyze(Throwable) walks the cause chain looking for the typed
        // exception. Pass it directly as both root and cause to exercise the same path.
        return analyzer.analyze(e);
    }

    @Test
    void unsetReasonProducesDescriptionAndAction() {
        final FailureAnalysis fa = analyze(new CryptoSecretConfigurationException(
                CryptoSecretConfigurationException.Reason.UNSET, "missing"));

        assertNotNull(fa, "the analyzer must match its declared exception type");
        assertTrue(fa.getDescription().contains("not set"),
                "expected unset description, got: " + fa.getDescription());
        assertTrue(fa.getAction().contains("openssl rand -base64 32"),
                "action must point at the canonical key-generation command");
        assertTrue(fa.getAction().contains(".env"),
                "action must mention .env so docker-compose users know where to put the key");
    }

    @Test
    void placeholderReasonCallsOutTheExampleFile() {
        final FailureAnalysis fa = analyze(new CryptoSecretConfigurationException(
                CryptoSecretConfigurationException.Reason.PLACEHOLDER, "still placeholder"));

        assertTrue(fa.getDescription().contains(".env.example"),
                "operator should see exactly what they copied: " + fa.getDescription());
        assertTrue(fa.getDescription().contains("replace-me-with-base64-of-32-random-bytes"),
                "the literal placeholder should be quoted so a grep finds it: "
                        + fa.getDescription());
    }

    @Test
    void notBase64ReasonHintsAtCommonGotchas() {
        final FailureAnalysis fa = analyze(new CryptoSecretConfigurationException(
                CryptoSecretConfigurationException.Reason.NOT_BASE64, "bad b64"));

        // Operators most often hit this with a Base64URL paste or a passphrase typed
        // in directly. The action should call out the fix; the description should call
        // out at least one common cause so the operator can spot what's wrong.
        assertTrue(fa.getDescription().toLowerCase().contains("base64"),
                "description should reference base64");
        assertTrue(fa.getDescription().contains("Base64URL")
                || fa.getDescription().contains("passphrase"),
                "description should hint at common gotchas (Base64URL or passphrase): "
                        + fa.getDescription());
    }

    @Test
    void wrongLengthReasonReportsActualLength() {
        final FailureAnalysis fa = analyze(new CryptoSecretConfigurationException(
                CryptoSecretConfigurationException.Reason.WRONG_LENGTH, 16, "too short"));

        assertTrue(fa.getDescription().contains("16"),
                "the actual decoded length should appear so the operator can confirm it: "
                        + fa.getDescription());
        assertTrue(fa.getDescription().contains("32"),
                "the expected length should appear too: " + fa.getDescription());
    }

    @Test
    void analyzerReturnsNullForUnrelatedException() {
        // Spring Boot ignores FailureAnalyzers that return null. AbstractFailureAnalyzer
        // already filters by exception type, but this guards against accidental matches
        // through the cause chain.
        assertNull(analyzer.analyze(new RuntimeException("not us")));
    }

    @Test
    void analyzerReturnsAnalysisWhenExceptionIsBuriedInCauseChain() {
        // Spring instantiates the service inside ConstructorResolver, so the typed
        // exception lands several frames deep. The analyzer must walk the cause chain
        // (AbstractFailureAnalyzer does this for us) — exercise the path.
        final CryptoSecretConfigurationException root = new CryptoSecretConfigurationException(
                CryptoSecretConfigurationException.Reason.UNSET, "missing");
        final RuntimeException wrapper1 = new RuntimeException("bean creation failed", root);
        final RuntimeException wrapper2 = new RuntimeException("context load failed", wrapper1);

        final FailureAnalysis fa = analyzer.analyze(wrapper2);

        assertNotNull(fa);
        assertEquals(root, fa.getCause(),
                "the analysis should reference the original typed exception, not a wrapper");
    }
}
