/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.webapp.security.PendingPhilterSignIn;
import ai.philterd.arbiter.webapp.security.PendingPhilterSignIn.Step;
import ai.philterd.arbiter.webapp.security.PhilterSessionAuthentication;
import ai.philterd.arbiter.webapp.security.PhilterSignInFlow;
import ai.philterd.arbiter.webapp.security.PhilterSignInService;
import ai.philterd.arbiter.webapp.security.PhilterSignInStepException;
import ai.philterd.arbiter.webapp.security.MfaQrCodes;
import ai.philterd.philter.model.MfaEnrollment;
import ai.philterd.philter.model.SignInResponse;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.philter.model.exceptions.SignInLockedException;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhilterSignInControllerTest {

    private PhilterSignInService signInService;
    private PhilterSignInFlow flow;
    private MfaQrCodes qrCodes;
    private SecurityContextRepository contextRepository;
    private SessionRegistry sessionRegistry;
    private PhilterSignInController controller;
    private MockHttpSession session;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        signInService = mock(PhilterSignInService.class);
        flow = mock(PhilterSignInFlow.class);
        qrCodes = mock(MfaQrCodes.class);
        contextRepository = mock(SecurityContextRepository.class);
        sessionRegistry = mock(SessionRegistry.class);
        controller = new PhilterSignInController(signInService, flow, qrCodes, contextRepository, sessionRegistry,
                new HttpSessionCsrfTokenRepository());
        session = new MockHttpSession();
        request = new MockHttpServletRequest();
        request.setSession(session);
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void pending(final Step step) {
        session.setAttribute(PendingPhilterSignIn.SESSION_ATTRIBUTE, PendingPhilterSignIn.of(step, "jdoe", "enc"));
    }

    @Test
    void stepPagesNeedAPendingStepOfTheirKind() {
        assertEquals("redirect:/login", controller.mfa(session));
        pending(Step.PASSWORD_CHANGE);
        assertEquals("redirect:/login", controller.mfa(session));
        assertEquals("sign-in-password", controller.password(session, new ExtendedModelMap()));
    }

    @Test
    void rightCodeSignsInUnderANewSessionId() throws Exception {
        pending(Step.MFA);
        final String oldId = session.getId();
        session.setAttribute(HttpSessionCsrfTokenRepository.class.getName() + ".CSRF_TOKEN", "pre-sign-in-token");
        final SignInResponse key = new SignInResponse();
        final PhilterSessionAuthentication auth = new PhilterSessionAuthentication("jdoe", "enc-key",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(signInService.completeSignIn(eq("enc"), eq("123456"), any(), any())).thenReturn(key);
        when(flow.complete(key, "jdoe")).thenReturn(auth);

        assertEquals("redirect:/", controller.completeMfa(" 123456 ", request, response));

        assertNotEquals(oldId, request.getSession().getId());
        assertNull(session.getAttribute(HttpSessionCsrfTokenRepository.class.getName() + ".CSRF_TOKEN"),
                "the pre-sign-in CSRF token is replaced");
        verify(sessionRegistry).registerNewSession(request.getSession().getId(), "jdoe");
        assertSame(auth, SecurityContextHolder.getContext().getAuthentication());
        verify(contextRepository).saveContext(any(SecurityContext.class), eq(request), eq(response));
        assertNull(session.getAttribute(PendingPhilterSignIn.SESSION_ATTRIBUTE));
    }

    @Test
    void wrongCodeUsesUpTheChallengeAndReturnsToSignIn() throws Exception {
        pending(Step.MFA);
        final UnauthorizedException wrong = new UnauthorizedException("no");
        when(signInService.completeSignIn(any(), any(), any(), any())).thenThrow(wrong);
        when(flow.refused("jdoe", wrong)).thenReturn(new BadCredentialsException("Bad credentials"));

        assertEquals("redirect:/login?code", controller.completeMfa("000000", request, response));
        assertNull(session.getAttribute(PendingPhilterSignIn.SESSION_ATTRIBUTE));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void aStepAfterTheCodeReplacesThePendingStep() throws Exception {
        pending(Step.MFA);
        final SignInResponse key = new SignInResponse();
        final PendingPhilterSignIn next = PendingPhilterSignIn.of(Step.PASSWORD_CHANGE, "jdoe", "enc-limited");
        when(signInService.completeSignIn(any(), any(), any(), any())).thenReturn(key);
        when(flow.complete(key, "jdoe")).thenThrow(new PhilterSignInStepException(next));

        assertEquals("redirect:/sign-in/password", controller.completeMfa("123456", request, response));
        assertSame(next, request.getSession().getAttribute(PendingPhilterSignIn.SESSION_ATTRIBUTE));
    }

    @Test
    void passwordChangeSendsThePersonBackToSignIn() throws Exception {
        pending(Step.PASSWORD_CHANGE);

        assertEquals("redirect:/login?passwordChanged", controller.changePassword("old-password-12345",
                "new-password-1234567", "new-password-1234567", session, new ExtendedModelMap()));

        verify(signInService).changePassword("enc", "old-password-12345", "new-password-1234567");
        assertNull(session.getAttribute(PendingPhilterSignIn.SESSION_ATTRIBUTE));
    }

    @Test
    void passwordChangeProblemsStayOnThePage() throws Exception {
        pending(Step.PASSWORD_CHANGE);
        final ExtendedModelMap model = new ExtendedModelMap();
        assertEquals("sign-in-password", controller.changePassword("old", "a", "b", session, model));
        assertNotNull(model.get("error"));
        verify(signInService, never()).changePassword(any(), any(), any());

        doThrow(new ClientException("Bad Request", 400, "too short"))
                .when(signInService).changePassword(any(), any(), any());
        final ExtendedModelMap rules = new ExtendedModelMap();
        assertEquals("sign-in-password", controller.changePassword("old", "short", "short", session, rules));
        assertEquals(true, rules.get("error").toString().contains("16 characters"));
    }

    @Test
    void lockedDuringPasswordChangeReturnsToSignIn() throws Exception {
        pending(Step.PASSWORD_CHANGE);
        doThrow(new SignInLockedException("locked", 60)).when(signInService).changePassword(any(), any(), any());

        assertEquals("redirect:/login?locked", controller.changePassword("old-password-12345",
                "new-password-1234567", "new-password-1234567", session, new ExtendedModelMap()));
        assertNull(session.getAttribute(PendingPhilterSignIn.SESSION_ATTRIBUTE));
    }

    @Test
    void enrollmentShowsTheQrCodeAndConfirmingSendsThePersonBackToSignIn() throws Exception {
        pending(Step.MFA_ENROLLMENT);
        final MfaEnrollment enrollment = new MfaEnrollment();
        enrollment.setSecret("JBSWY3DPEHPK3PXP");
        enrollment.setOtpauthUri("otpauth://totp/Philter:jdoe?secret=JBSWY3DPEHPK3PXP&issuer=Philter");
        when(signInService.startMfaEnrollment("enc", "a-password-of-16+")).thenReturn(enrollment);
        when(qrCodes.dataUri(anyString())).thenReturn("data:image/png;base64,AAAA");
        final ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("sign-in-mfa-enrollment", controller.startMfaEnrollment("a-password-of-16+", session, model));
        assertEquals("data:image/png;base64,AAAA", model.get("qrCodeDataUri"));
        verify(qrCodes).dataUri(enrollment.getOtpauthUri());

        assertEquals("redirect:/login?mfaEnrolled",
                controller.confirmMfaEnrollment("123456", session, new ExtendedModelMap()));
        verify(signInService).confirmMfaEnrollment("enc", "123456");
        assertNull(session.getAttribute(PendingPhilterSignIn.SESSION_ATTRIBUTE));
    }

    @Test
    void keyIsRevokedWhenTheSessionCannotBeEstablished() throws Exception {
        pending(Step.MFA);
        final SignInResponse key = new SignInResponse();
        final PhilterSessionAuthentication auth = new PhilterSessionAuthentication("jdoe", "enc-key",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(signInService.completeSignIn(any(), any(), any(), any())).thenReturn(key);
        when(flow.complete(key, "jdoe")).thenReturn(auth);
        doThrow(new IllegalStateException("session store down")).when(contextRepository)
                .saveContext(any(), any(), any());

        assertEquals("redirect:/login?unavailable", controller.completeMfa("123456", request, response));
        verify(flow).signOutQuietly("enc-key");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
