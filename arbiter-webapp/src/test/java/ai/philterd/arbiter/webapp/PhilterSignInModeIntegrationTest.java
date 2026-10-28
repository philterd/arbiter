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
import ai.philterd.arbiter.webapp.controllers.AdminController;
import ai.philterd.arbiter.webapp.controllers.InvitationController;
import ai.philterd.arbiter.webapp.controllers.LoginController;
import ai.philterd.arbiter.webapp.controllers.PhilterSignInController;
import ai.philterd.arbiter.webapp.controllers.PhilterUsersController;
import ai.philterd.arbiter.webapp.security.MongoUserDetailsService;
import ai.philterd.arbiter.webapp.security.PhilterSignInFlow;
import ai.philterd.arbiter.webapp.security.PhilterSignInService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The security wiring when people sign in through Philter, built from the real {@link SecurityConfig}. */
@WebMvcTest(controllers = {LoginController.class, PhilterSignInController.class, InvitationController.class,
        AdminController.class, PhilterUsersController.class},
        properties = {"arbiter.sign-in.mode= Philter ",
                "arbiter.philter.url=http://philter.invalid:8080"})
@Import({SecurityConfig.class, MongoUserDetailsService.class})
class PhilterSignInModeIntegrationTest extends WebMvcSliceMocks {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext context;
    @MockBean private PhilterSignInService philterSignInService;
    @MockBean private PhilterSignInFlow philterSignInFlow;
    @MockBean private PhilterUsersService philterUsersService;
    @MockBean private PhilterUserProvisioner philterUserProvisioner;

    @Test
    void validArbiterApiKeyIsRefused() throws Exception {
        final User user = new User();
        user.setEmail("api@example.com");
        user.setRoles(Set.of("ADMIN"));
        when(apiKeyHashingService.hash("a-valid-key")).thenReturn("hash");
        when(userRepository.findByApiKey("hash")).thenReturn(Optional.of(user));

        mockMvc.perform(post("/api/v1/ingest").header("Authorization", "Bearer a-valid-key")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginFormAsksForAUsername() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"username\"")));
    }

    @Test
    void signInStepsArePresentAndInvitationsAreNot() throws Exception {
        assertEquals(1, context.getBeanNamesForType(PhilterSignInController.class).length);
        assertEquals(0, context.getBeanNamesForType(InvitationController.class).length);
        mockMvc.perform(get("/sign-in/mfa")).andExpect(redirectedUrl("/login"));
        assertTrue(mockMvc.perform(get("/invitations/some-token")).andReturn().getResponse().getStatus() >= 400);
    }

    @Test
    void usersPageIsPhiltersAndRenders() throws Exception {
        assertEquals(0, context.getBeanNamesForType(AdminController.class).length);
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
