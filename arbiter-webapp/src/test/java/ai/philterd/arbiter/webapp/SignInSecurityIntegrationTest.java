/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp;

import ai.philterd.arbiter.model.User;
import ai.philterd.arbiter.webapp.controllers.LoginController;
import ai.philterd.arbiter.webapp.controllers.PhilterSignInController;
import ai.philterd.arbiter.webapp.controllers.PhilterUsersController;
import ai.philterd.arbiter.webapp.security.PhilterApiKeys;
import ai.philterd.arbiter.webapp.security.PhilterUserProvisioner;
import ai.philterd.arbiter.webapp.security.PhilterUsersService;
import ai.philterd.arbiter.webapp.security.PhilterSessionAuthentication;
import ai.philterd.arbiter.webapp.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The sign-in and API key wiring, built from the real {@link SecurityConfig}. */
@WebMvcTest(controllers = {LoginController.class, PhilterSignInController.class, PhilterUsersController.class})
@Import(SecurityConfig.class)
class SignInSecurityIntegrationTest extends WebMvcSliceMocks {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext context;
    @MockBean private PhilterUsersService philterUsersService;
    @MockBean private PhilterUserProvisioner philterUserProvisioner;

    @Test
    void philterApiKeyAuthenticatesTheApi() throws Exception {
        when(philterApiKeys.resolve("sk_valid")).thenReturn(new PhilterApiKeys.Caller("ci", "USER"));

        mockMvc.perform(get("/api/v1/batches").header("Authorization", "Bearer sk_valid"))
                .andExpect(status().isOk());
    }

    @Test
    void refusedApiKeysAreAnsweredByReason() throws Exception {
        when(philterApiKeys.resolve("sk_rejected"))
                .thenThrow(new PhilterApiKeys.RefusedException(PhilterApiKeys.Refusal.INVALID));
        when(philterApiKeys.resolve("sk_no_scope"))
                .thenThrow(new PhilterApiKeys.RefusedException(PhilterApiKeys.Refusal.MISSING_SCOPE));
        when(philterApiKeys.resolve("sk_unchecked"))
                .thenThrow(new PhilterApiKeys.RefusedException(PhilterApiKeys.Refusal.UNAVAILABLE));

        mockMvc.perform(get("/api/v1/batches").header("Authorization", "Bearer sk_rejected"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/batches").header("Authorization", "Bearer sk_no_scope"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/batches").header("Authorization", "Bearer sk_unchecked"))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/api/v1/batches")).andExpect(status().isUnauthorized());
    }

    @Test
    void bearerKeysAreNotCheckedOutsideTheApi() throws Exception {
        mockMvc.perform(get("/admin/users").accept("text/html").header("Authorization", "Bearer sk_valid"))
                .andExpect(redirectedUrlPattern("**/login"));
        org.mockito.Mockito.verifyNoInteractions(philterApiKeys);
    }

    @Test
    void loginFormAsksForAUsername() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"username\"")));
    }

    @Test
    void signInStepsArePresentAndLocalOnesAreNot() throws Exception {
        assertEquals(1, context.getBeanNamesForType(PhilterSignInController.class).length);
        mockMvc.perform(get("/sign-in/mfa")).andExpect(redirectedUrl("/login"));
        // The local MFA and invitation pages are gone; unauthenticated callers are sent to sign in.
        mockMvc.perform(get("/mfa").accept("text/html")).andExpect(redirectedUrlPattern("**/login"));
        mockMvc.perform(get("/invitations/some-token").accept("text/html"))
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void usersPageIsPhiltersAndRenders() throws Exception {
        final ai.philterd.philter.model.User reviewer = new ai.philterd.philter.model.User();
        reviewer.setUsername("j.doe@x y");
        reviewer.setRole("user");
        reviewer.setActive(true);
        reviewer.setPasswordSet(true);
        reviewer.setMfaEnabled(true);
        final ai.philterd.philter.model.User service = new ai.philterd.philter.model.User();
        service.setUsername("ci");
        service.setRole("user");
        service.setActive(false);
        when(philterUsersService.listUsers("enc-admin")).thenReturn(java.util.List.of(reviewer, service));
        final ai.philterd.philter.model.CurrentUser me = new ai.philterd.philter.model.CurrentUser();
        me.setUsername("boss");
        me.setRole("admin");
        me.setActive(true);
        when(philterSignInService.currentUser("enc-admin")).thenReturn(me);
        final User record = new User();
        record.setId("r0");
        record.setEmail("boss");
        record.setRoles(Set.of("ADMIN"));
        when(userRepository.findByEmail("boss")).thenReturn(Optional.of(record));
        final PhilterSessionAuthentication admin = new PhilterSessionAuthentication("boss", "enc-admin",
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));

        mockMvc.perform(get("/admin/users").with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.authentication(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/users/j.doe@x%20y/remove-mfa")))
                .andExpect(result -> {
                    final String html = result.getResponse().getContentAsString().replaceAll("(?s)<!--.*?-->", "");
                    assertEquals(html.split("<div\\b", -1).length, html.split("</div>", -1).length,
                            "every div is closed");
                })
                .andExpect(content().string(org.hamcrest.Matchers.containsString("API only")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/users/ci/reactivate")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/admin/users/ci/password"))));
    }

    private PhilterSessionAuthentication signedIn(final String role, final String philterRole) throws Exception {
        final ai.philterd.philter.model.CurrentUser me = new ai.philterd.philter.model.CurrentUser();
        me.setUsername("person");
        me.setRole(philterRole);
        me.setActive(true);
        when(philterSignInService.currentUser("enc-person")).thenReturn(me);
        final User record = new User();
        record.setId("p1");
        record.setEmail("person");
        record.setRoles(Set.of(role));
        when(userRepository.findByEmail("person")).thenReturn(Optional.of(record));
        return new PhilterSessionAuthentication("person", "enc-person",
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void auditorCannotChangeUsers() throws Exception {
        final PhilterSessionAuthentication auditor = signedIn("AUDITOR", "user");

        mockMvc.perform(post("/admin/users/jdoe/deactivate")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auditor))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(philterUsersService);
    }

    @Test
    void changesNeedACsrfToken() throws Exception {
        final PhilterSessionAuthentication admin = signedIn("ADMIN", "admin");

        mockMvc.perform(post("/admin/users/jdoe/deactivate")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(admin)))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(philterUsersService);
    }
}
