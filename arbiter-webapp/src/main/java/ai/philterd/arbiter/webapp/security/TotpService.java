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

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.OptionalLong;

@Service
public class TotpService {

    private static final String ISSUER = "Arbiter";
    /** Standard RFC 6238 step size. Matches Google Authenticator and similar apps. */
    private static final int STEP_SECONDS = 30;
    /** ±1 step of clock skew tolerance (so a 30-second drift in either direction
     *  still verifies). Matches the samstevens DefaultCodeVerifier's default. */
    private static final int DISCREPANCY_STEPS = 1;

    private final DefaultSecretGenerator secretGenerator = new DefaultSecretGenerator(32);
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator();
    private final ZxingPngQrGenerator qrGenerator = new ZxingPngQrGenerator();
    /** Test seam — overridable via the package-private constructor below. */
    private final Clock clock;

    public TotpService() {
        this(Clock.systemUTC());
    }

    TotpService(final Clock clock) {
        this.clock = clock;
    }

    public String generateSecret() {
        return secretGenerator.generate();
    }

    /**
     * Verify a candidate TOTP code and return the time-step that accepted it, or
     * {@link OptionalLong#empty()} if the code is invalid. The caller is expected
     * to compare the returned step against the user's {@code lastTotpStep} and
     * refuse re-use within the same step (R2-F11): the samstevens library's
     * built-in {@code isValidCode} accepts a code anywhere in the ±1 window with
     * no tracking, so a captured code can be replayed for 30–60 seconds against
     * a separate browser session. Tracking the step closes that window.
     */
    public OptionalLong verifyAndReturnStep(final String secret, final String code) {
        if (secret == null || code == null) return OptionalLong.empty();
        final String trimmed = code.trim();
        if (trimmed.isEmpty()) return OptionalLong.empty();
        final long nowStep = clock.instant().getEpochSecond() / STEP_SECONDS;
        for (long step = nowStep - DISCREPANCY_STEPS; step <= nowStep + DISCREPANCY_STEPS; step++) {
            try {
                final String expected = codeGenerator.generate(secret, step);
                // Constant-time compare so an attacker who sends partial codes can't
                // distinguish "wrong first digit" from "wrong last digit" via timing.
                if (MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        trimmed.getBytes(StandardCharsets.UTF_8))) {
                    return OptionalLong.of(step);
                }
            } catch (CodeGenerationException ignored) {
                // Bad secret would be a server-side bug, not the caller's; treat as
                // verification failure rather than propagating.
            }
        }
        return OptionalLong.empty();
    }

    /** Convenience wrapper for callers that don't track replay state. Use
     *  {@link #verifyAndReturnStep(String, String)} on the login/enrol/disable paths
     *  so the step can be checked against {@code User.lastTotpStep}. */
    public boolean verify(final String secret, final String code) {
        return verifyAndReturnStep(secret, code).isPresent();
    }

    /** Returns a base64-encoded PNG data URI for the QR code. */
    public String qrCodeDataUri(final String email, final String secret) {
        final QrData data = new QrData.Builder()
                .label(email)
                .secret(secret)
                .issuer(ISSUER)
                .build();
        try {
            final byte[] png = qrGenerator.generate(data);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        } catch (QrGenerationException e) {
            throw new IllegalStateException("QR code generation failed", e);
        }
    }
}
