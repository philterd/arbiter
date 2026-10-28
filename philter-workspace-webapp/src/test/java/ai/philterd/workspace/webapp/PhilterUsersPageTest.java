/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp;

import ai.philterd.philter.model.GetUsersResponse;
import ai.philterd.philter.model.ListOptions;
import ai.philterd.philter.model.User;
import ai.philterd.workspace.webapp.controllers.PhilterUsersController;
import ai.philterd.workspace.webapp.security.PhilterUserProvisioner;
import ai.philterd.workspace.webapp.security.PhilterUsersService;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** The Users page on the shared list fragments: Philter pages, searches, filters, and sorts the users. */
@WebMvcTest(controllers = PhilterUsersController.class)
@Import(SecurityConfig.class)
class PhilterUsersPageTest extends WebMvcSliceMocks {

    @Autowired private MockMvc mockMvc;
    @MockBean private PhilterUsersService usersService;
    @MockBean private PhilterUserProvisioner provisioner;

    private static GetUsersResponse page(final long total, final int from, final int count) {
        final List<User> users = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            final User user = new User();
            user.setUsername(String.format("user-%03d", i));
            user.setRole("user");
            user.setActive(true);
            user.setPasswordSet(true);
            users.add(user);
        }
        final GetUsersResponse page = new GetUsersResponse();
        page.setUsers(users);
        page.setTotal(total);
        return page;
    }

    @Test
    void theSearchFiltersSortAndPageGoToPhilter() throws Exception {
        when(usersService.listPage(eq("enc-session-key"), any())).thenReturn(page(60, 26, 25));

        final String html = mockMvc.perform(get("/admin/users?q=user&role=user&active=true&sort=username"
                        + "&order=desc&offset=25").with(signedIn("boss", "ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("admin-philter-users"))
                .andReturn().getResponse().getContentAsString();

        final ArgumentCaptor<ListOptions> options = ArgumentCaptor.forClass(ListOptions.class);
        verify(usersService).listPage(eq("enc-session-key"), options.capture());
        assertEquals(25, options.getValue().getOffset());
        assertEquals(25, options.getValue().getLimit());
        assertEquals("user", options.getValue().getSearch());
        assertEquals("user", options.getValue().getRole());
        assertEquals(Boolean.TRUE, options.getValue().getActive());
        assertEquals("username", options.getValue().getSort());
        assertEquals(ListOptions.DESCENDING, options.getValue().getOrder());

        assertTrue(html.contains("26 to 50 of 60"), html);
        assertTrue(html.contains("href=\"/admin/users?q=user&amp;role=user&amp;active=true&amp;sort=username"
                + "&amp;order=desc&amp;offset=50\" rel=\"next\""), html);
        assertTrue(html.contains("<option value=\"user\" selected=\"selected\">User</option>"));
        assertTrue(html.contains("<option value=\"true\" selected=\"selected\">Active</option>"));
        assertFalse(html.contains("name=\"owner\""), "Philter's users have no owner");
    }

    @Test
    void unknownFilterValuesAreNotSentToPhilter() throws Exception {
        when(usersService.listPage(eq("enc-session-key"), any())).thenReturn(page(0, 0, 0));

        mockMvc.perform(get("/admin/users?role=superuser&active=maybe&sort=password")
                .with(signedIn("boss", "ADMIN"))).andExpect(status().isOk());

        final ArgumentCaptor<ListOptions> options = ArgumentCaptor.forClass(ListOptions.class);
        verify(usersService).listPage(eq("enc-session-key"), options.capture());
        assertEquals(null, options.getValue().getRole());
        assertEquals(null, options.getValue().getActive());
        assertEquals("username", options.getValue().getSort());
    }

    @Test
    void destructiveActionsAreConfirmedWithOrWithoutJavaScript() throws Exception {
        final GetUsersResponse users = page(1, 1, 1);
        users.getUsers().get(0).setMfaEnabled(true);
        when(usersService.listPage(eq("enc-session-key"), any())).thenReturn(users);

        final String html = mockMvc.perform(get("/admin/users").with(signedIn("boss", "ADMIN")))
                .andReturn().getResponse().getContentAsString();
        assertTrue(java.util.regex.Pattern.compile(
                "<a href=\"/admin/users/user-001/confirm/deactivate\"[^>]*data-confirm-dialog=\"deactivate-0\"")
                .matcher(html).find(), html);
        assertTrue(html.contains("data-confirm-dialog=\"remove-mfa-0\""));
        assertTrue(html.contains("action=\"/admin/users/user-001/deactivate\" method=\"post\""));
        assertFalse(html.contains("onsubmit"), "the CSP refuses inline handlers");

        final String confirm = mockMvc.perform(get("/admin/users/user-001/confirm/deactivate")
                        .with(signedIn("boss", "ADMIN")))
                .andExpect(view().name("confirm")).andReturn().getResponse().getContentAsString();
        assertTrue(confirm.contains("Deactivate user-001?"), confirm);
        assertTrue(confirm.contains("action=\"/admin/users/user-001/deactivate\" method=\"post\""));
        assertTrue(confirm.contains("href=\"/admin/users\""), "Cancel goes back");

        mockMvc.perform(get("/admin/users/user-001/confirm/unknown").with(signedIn("boss", "ADMIN")))
                .andExpect(status().is3xxRedirection());
    }
}
