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

import ai.philterd.philter.model.SignInResponse;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhilterAuthenticationProviderTest {

    private PhilterSignInService signInService;
    private PhilterSignInFlow flow;
    private PhilterAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        signInService = mock(PhilterSignInService.class);
        flow = mock(PhilterSignInFlow.class);
        provider = new PhilterAuthenticationProvider(signInService, flow);
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("User-Agent", "Test Browser");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void passesThePersonsAddressAndUserAgentToPhilter() throws Exception {
        final SignInResponse response = new SignInResponse();
        final PhilterSessionAuthentication signedIn = new PhilterSessionAuthentication("jdoe", "enc",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(signInService.signIn("jdoe", "a-password-of-16+", "203.0.113.7", "Test Browser")).thenReturn(response);
        when(flow.complete(response, "jdoe")).thenReturn(signedIn);

        assertSame(signedIn, provider.authenticate(
                new UsernamePasswordAuthenticationToken(" jdoe ", "a-password-of-16+")));
    }

    @Test
    void blankUsernameOrPasswordIsRefusedWithoutCallingPhilter() throws Exception {
        assertThrows(BadCredentialsException.class,
                () -> provider.authenticate(new UsernamePasswordAuthenticationToken("jdoe", "")));
        assertThrows(BadCredentialsException.class,
                () -> provider.authenticate(new UsernamePasswordAuthenticationToken(" ", "pw")));
        verify(signInService, never()).signIn(any(), any(), any(), any());
    }

    @Test
    void philterErrorsAreTranslatedByTheFlow() throws Exception {
        final UnauthorizedException unauthorized = new UnauthorizedException("no");
        when(signInService.signIn(any(), any(), any(), any())).thenThrow(unauthorized);
        when(flow.refused("jdoe", unauthorized)).thenReturn(new BadCredentialsException("Bad credentials"));

        assertThrows(BadCredentialsException.class,
                () -> provider.authenticate(new UsernamePasswordAuthenticationToken("jdoe", "wrong")));
    }

    @Test
    void supportsTheSignInForm() {
        assertTrue(provider.supports(UsernamePasswordAuthenticationToken.class));
    }

    @Test
    void anUnsupportedPhilterIsRefusedBeforeThePasswordIsSent() throws Exception {
        final ai.philterd.philter.model.StatusResponse health = new ai.philterd.philter.model.StatusResponse();
        health.setApplicationVersion("5.0.0");
        when(signInService.health()).thenReturn(health);
        final UnsupportedPhilterException refused = new UnsupportedPhilterException("unsupported");
        when(flow.unsupported(org.mockito.ArgumentMatchers.eq("jdoe"), any())).thenReturn(refused);

        assertSame(refused, assertThrows(UnsupportedPhilterException.class, () -> provider.authenticate(
                new UsernamePasswordAuthenticationToken("jdoe", "a-password-of-16+"))));
        verify(signInService, never()).signIn(any(), any(), any(), any());
    }
}
