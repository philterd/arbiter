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
import ai.philterd.arbiter.webapp.controllers.InvitationController;
import ai.philterd.arbiter.webapp.controllers.LoginController;
import ai.philterd.arbiter.webapp.controllers.PhilterSignInController;
import ai.philterd.arbiter.webapp.security.MongoUserDetailsService;
import ai.philterd.arbiter.webapp.security.PhilterSignInFlow;
import ai.philterd.arbiter.webapp.security.PhilterSignInService;
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
@WebMvcTest(controllers = {LoginController.class, PhilterSignInController.class, InvitationController.class},
        properties = {"arbiter.sign-in.mode= Philter ",
                "arbiter.sign-in.philter-url=http://philter.invalid:8080"})
@Import({SecurityConfig.class, MongoUserDetailsService.class})
class PhilterSignInModeIntegrationTest extends WebMvcSliceMocks {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext context;
    @MockBean private PhilterSignInService philterSignInService;
    @MockBean private PhilterSignInFlow philterSignInFlow;

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
}
