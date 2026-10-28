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
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.philter.model.CurrentUser;
import ai.philterd.philter.model.SignInResponse;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.philter.model.exceptions.ErrorReasons;
import ai.philterd.philter.model.exceptions.SignInLockedException;
import ai.philterd.philter.model.exceptions.SignInRateLimitedException;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Turns Philter's answer to a sign-in into an Arbiter sign-in: the person is signed in, or has another
 * step to complete ({@link PhilterSignInStepException}), or is refused. Every outcome except a pending
 * step writes a {@code LOGIN} audit entry, without the password.
 */
@Service
public class PhilterSignInFlow {

    private static final Logger log = LoggerFactory.getLogger(PhilterSignInFlow.class);
    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final PhilterSignInService signInService;
    private final PhilterUserProvisioner provisioner;
    private final AuditLogService auditLogService;

    public PhilterSignInFlow(final PhilterSignInService signInService,
                             final PhilterUserProvisioner provisioner,
                             final AuditLogService auditLogService) {
        this.signInService = signInService;
        this.provisioner = provisioner;
        this.auditLogService = auditLogService;
    }

    /**
     * Completes a sign-in from Philter's response to the password or MFA step.
     *
     * @throws PhilterSignInStepException when the person must enter an MFA code, change their password,
     *                                    or enroll in MFA first
     */
    public PhilterSessionAuthentication complete(final SignInResponse response, final String username) {
        if (response.getChallenge() != null && !response.getChallenge().isBlank()) {
            throw new PhilterSignInStepException(PendingPhilterSignIn.of(PendingPhilterSignIn.Step.MFA,
                    username, signInService.encrypt(response.getChallenge())));
        }
        if (response.getApiKey() == null || response.getApiKey().isBlank()) {
            throw fail(username, "NoSessionKey", new AuthenticationServiceException("Philter issued no session key."));
        }
        final String encryptedKey = signInService.encrypt(response.getApiKey());
        final String name = response.getUsername() == null || response.getUsername().isBlank()
                ? username : response.getUsername();
        if (response.isPasswordChangeRequired()) {
            throw new PhilterSignInStepException(PendingPhilterSignIn.of(
                    PendingPhilterSignIn.Step.PASSWORD_CHANGE, name, encryptedKey));
        }
        if (response.isMfaEnrollmentRequired()) {
            throw new PhilterSignInStepException(PendingPhilterSignIn.of(
                    PendingPhilterSignIn.Step.MFA_ENROLLMENT, name, encryptedKey));
        }

        // Until the person is signed in, any failure revokes the key Philter just issued.
        final CurrentUser me;
        final String role;
        try {
            me = signInService.currentUser(encryptedKey);
            role = provisioner.provision(me.getUsername(), me.getRole());
            auditLogService.logForUser(me.getUsername(), "LOGIN", "User", null,
                    AuditLogService.OUTCOME_SUCCESS, Map.of("method", "philter"));
        } catch (Exception e) {
            signOutQuietly(encryptedKey);
            throw refused(name, e);
        }
        return new PhilterSessionAuthentication(me.getUsername(), encryptedKey,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    /**
     * Audits a refused sign-in and translates Philter's error into the Spring Security exception the
     * login page shows. A wrong password, an unknown username, and a deactivated user are all the same
     * {@link BadCredentialsException}, as Philter makes them, so the page does not reveal which usernames
     * exist. A locked username, an address over the rate limit, and locked MFA are all
     * {@link LockedException}.
     */
    public AuthenticationException refused(final String username, final Exception e) {
        if (e instanceof UnauthorizedException) {
            return fail(username, "BadCredentials", new BadCredentialsException("Bad credentials"));
        }
        if (e instanceof SignInLockedException) {
            return fail(username, "Locked", new LockedException("Sign-in is locked."));
        }
        if (e instanceof SignInRateLimitedException) {
            return fail(username, "RateLimited", new LockedException("Too many sign-in attempts."));
        }
        if (e instanceof ClientException ce && ErrorReasons.MFA_LOCKED.equals(ce.getReason())) {
            return fail(username, "MfaLocked", new LockedException("MFA is locked."));
        }
        if (e instanceof ClientException ce && ce.getStatusCode() == 404) {
            log.warn("Philter answered sign-in with 404. Set PASSWORD_SIGN_IN_ENABLED=true on the Philter "
                    + "named by ARBITER_PHILTER_URL.");
            return fail(username, "SignInDisabled", new AuthenticationServiceException("Sign-in is unavailable."));
        }
        log.warn("Sign-in through Philter failed: {}", PhilterClients.describe(e));
        return fail(username, "Unavailable", new AuthenticationServiceException("Sign-in is unavailable."));
    }

    /** Audits a sign-in refused because Arbiter does not support the Philter's version. */
    public AuthenticationException unsupported(final String username, final String message) {
        return fail(username, "UnsupportedPhilter", new UnsupportedPhilterException(message));
    }

    /** Revokes a session key, logging only the kind of failure. */
    public void signOutQuietly(final String encryptedKey) {
        try {
            signInService.signOut(encryptedKey);
        } catch (Exception e) {
            log.warn("Could not revoke a Philter session key: {}", PhilterClients.describe(e));
        }
    }

    public static String clientAddress(final HttpServletRequest request) {
        return request == null ? null : request.getRemoteAddr();
    }

    public static String userAgent(final HttpServletRequest request) {
        final String agent = request == null ? null : request.getHeader("User-Agent");
        if (agent == null) {
            return null;
        }
        return agent.length() > MAX_USER_AGENT_LENGTH ? agent.substring(0, MAX_USER_AGENT_LENGTH) : agent;
    }

    private AuthenticationException fail(final String username, final String reason,
                                         final AuthenticationException e) {
        auditLogService.logForUser(username, "LOGIN", "User", null, AuditLogService.OUTCOME_FAILURE,
                Map.of("reason", reason, "method", "philter"));
        return e;
    }
}
