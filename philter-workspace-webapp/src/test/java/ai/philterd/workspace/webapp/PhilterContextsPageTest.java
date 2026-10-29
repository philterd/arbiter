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

import ai.philterd.philter.model.ContextDetails;
import ai.philterd.philter.model.ContextSummary;
import ai.philterd.philter.model.GetContextsAcrossUsersResponse;
import ai.philterd.philter.model.GetContextsResponse;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.workspace.webapp.controllers.PhilterContextsController;
import ai.philterd.workspace.webapp.security.PhilterContextsService;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** The Contexts tab on Philter's Contexts API, as the signed-in person. */
@WebMvcTest(controllers = PhilterContextsController.class)
@Import(SecurityConfig.class)
class PhilterContextsPageTest extends WebMvcSliceMocks {

    private static final String KEY = "enc-session-key";

    @Autowired private MockMvc mockMvc;
    @MockBean private PhilterContextsService contexts;

    private static ContextSummary summary(final String name, final String owner) {
        final ContextSummary context = new ContextSummary();
        context.setName(name);
        context.setCreatedAt("2026-10-01T10:00:00Z");
        context.setOwner(owner);
        return context;
    }

    private static GetContextsResponse page(final ContextSummary... contexts) {
        final GetContextsResponse page = new GetContextsResponse();
        page.setContexts(List.of(contexts));
        page.setTotal(contexts.length);
        return page;
    }

    private static ContextDetails details(final long size, final boolean disambiguation, final String scope,
                                          final boolean ledger) {
        final ContextDetails details = new ContextDetails();
        details.setSize(size);
        details.setFilterTypes(size == 0 ? Map.of() : Map.of("email-address", size - 1));
        details.setUntyped(size == 0 ? 0 : 1);
        details.setEntityTypeDisambiguation(disambiguation);
        details.setDisambiguationScope(scope);
        details.setLedger(ledger);
        return details;
    }

    private String html(final String url, final String role) throws Exception {
        return mockMvc.perform(get(url).with(signedIn("boss", role))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theListIsThePersonsOwnContextsWithTheirEntryCountsAndActions() throws Exception {
        when(contexts.list(eq(KEY), isNull(), any())).thenReturn(page(summary("claims", null)));
        when(contexts.details(KEY, "claims", null)).thenReturn(details(12, true, "context", true));

        final String html = html("/policies/contexts", "ADMIN");

        assertTrue(html.contains(">claims<") && html.contains(">12<"), html);
        assertTrue(html.contains("Disambiguation within the context; ledger on"), "settings");
        assertTrue(html.contains("href=\"/policies/contexts/view?name=claims\""));
        assertTrue(html.contains("href=\"/policies/contexts/edit?name=claims\""));
        assertTrue(html.contains("data-confirm-dialog=\"clear-0\"") && html.contains("data-confirm-dialog=\"delete-0\""),
                "Clear and Delete are confirmed");
        assertTrue(html.contains("Its 12 entries are deleted"), "the clear confirmation counts them");
        assertTrue(html.contains("Philter refuses redactions that name it, such as a batch&#39;s"), "the delete warning");
        assertTrue(html.contains("href=\"/policies/contexts/new\"") && html.contains("name=\"owner\""));
    }

    @Test
    void aContextWhoseDetailsCannotBeReadIsStillListed() throws Exception {
        when(contexts.list(eq(KEY), isNull(), any())).thenReturn(page(summary("claims", null)));
        when(contexts.details(KEY, "claims", null)).thenThrow(new ClientException("Bad", 500, null));
        assertTrue(html("/policies/contexts", "ADMIN").contains(">Unavailable<"));
    }

    @Test
    void anAdministratorCanListEveryUsersContextsWithoutReadingEachOne() throws Exception {
        final GetContextsAcrossUsersResponse all = new GetContextsAcrossUsersResponse();
        all.setContexts(List.of(summary("default", "jdoe"), summary("default", "ann")));
        all.setTotal(2);
        when(contexts.listAcrossUsers(eq(KEY), any())).thenReturn(all);

        final String html = html("/policies/contexts?all=true", "ADMIN");

        assertTrue(html.contains(">jdoe<") && html.contains(">ann<"), html);
        verify(contexts, never()).details(any(), any(), any());
        assertFalse(html.contains(">Entries<"), "no counts for every user's");
        assertTrue(html.contains("href=\"/policies/contexts/edit?name=default&amp;owner=ann\""));
        assertFalse(html.contains("href=\"/policies/contexts/new"), "a new context needs one owner");
    }

    @Test
    void reachingAnotherUserWithoutCrossUserAccessIsExplained() throws Exception {
        when(contexts.list(eq(KEY), eq("ws"), any())).thenThrow(new ClientException("Not Found", 404, null));
        assertTrue(html("/policies/contexts?owner=ws", "ADMIN").contains("ADMIN_CROSS_USER_ACCESS_ENABLED=true"));
    }

    @Test
    void auditorsReadContextsButCannotChooseAnOwnerOrChangeThem() throws Exception {
        when(contexts.list(eq(KEY), isNull(), any())).thenReturn(page(summary("claims", null)));
        when(contexts.details(KEY, "claims", null)).thenReturn(details(2, false, "document", false));

        final String html = html("/policies/contexts?owner=boss&all=true", "AUDITOR");

        verify(contexts, never()).listAcrossUsers(any(), any());
        assertFalse(html.contains("name=\"owner\""), "no owner filter");
        assertFalse(html.contains("/policies/contexts/edit") || html.contains("data-confirm-dialog=\""), "read-only");
        assertTrue(html.contains("href=\"/policies/contexts/view?name=claims\""));
        mockMvc.perform(post("/policies/contexts/clear").with(signedIn("boss", "AUDITOR")).with(csrf())
                .param("name", "claims")).andExpect(status().isForbidden());
        mockMvc.perform(get("/policies/contexts/edit").param("name", "claims").with(signedIn("boss", "AUDITOR")))
                .andExpect(redirectedUrl("/policies/contexts/view?name=claims"));
        mockMvc.perform(get("/policies/contexts/new").with(signedIn("boss", "AUDITOR")))
                .andExpect(redirectedUrl("/policies/contexts"));
    }

    @Test
    void theViewShowsEntriesByFilterTypeAndTheSettings() throws Exception {
        when(contexts.details(KEY, "claims", null)).thenReturn(details(4, true, "document", false));

        final String html = html("/policies/contexts/view?name=claims", "ADMIN");

        assertTrue(html.contains("4 entries") && html.contains(">email-address<") && html.contains(">3<"), html);
        assertTrue(html.contains("No filter type"), "untyped entries");
        assertTrue(html.contains("Disambiguation within each document"));
    }

    @Test
    void creatingSendsTheNameAndSettings() throws Exception {
        mockMvc.perform(post("/policies/contexts/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", " claims ").param("disambiguation", "true").param("scope", "context")
                        .param("owner", "ws"))
                .andExpect(redirectedUrl("/policies/contexts?owner=ws"))
                .andExpect(flash().attribute("success", "Context \"claims\" created."));
        verify(contexts).create(KEY, "claims", true, "context", false, "ws");
    }

    @Test
    void aNamePhilterWouldRefuseIsNotSent() throws Exception {
        final String html = mockMvc.perform(post("/policies/contexts/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "a;b"))
                .andExpect(view().name("context-form")).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("A context name cannot contain"), html);
        verifyNoInteractions(contexts);
    }

    @Test
    void philtersRefusalsAreExplainedByTheirReason() throws Exception {
        doThrow(new ClientException("Conflict", 409, "Context already exists.", "context_exists"))
                .when(contexts).create(any(), any(), anyBoolean(), any(), anyBoolean(), any());
        final String exists = mockMvc.perform(post("/policies/contexts/new").with(signedIn("boss", "ADMIN"))
                        .with(csrf()).param("name", "claims"))
                .andExpect(view().name("context-form")).andReturn().getResponse().getContentAsString();
        assertTrue(exists.contains("A context with that name already exists."), exists);

        doThrow(new ClientException("Conflict", 409, "limit", "context_limit_reached"))
                .when(contexts).create(any(), eq("more"), anyBoolean(), any(), anyBoolean(), any());
        final String limit = mockMvc.perform(post("/policies/contexts/new").with(signedIn("boss", "ADMIN"))
                        .with(csrf()).param("name", "more"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(limit.contains("at most 10 contexts"), limit);

        doThrow(new ClientException("Conflict", 409, "Context has pending or processing redaction jobs; cannot delete.",
                "context_in_use")).when(contexts).delete(KEY, "claims", null);
        mockMvc.perform(post("/policies/contexts/delete").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "claims"))
                .andExpect(redirectedUrl("/policies/contexts"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.startsWith(
                        "Redaction jobs that use this context are pending or running")));
    }

    @Test
    void editingSendsOnlyTheSettingsThatChanged() throws Exception {
        when(contexts.details(KEY, "claims", null)).thenReturn(details(0, true, "document", false));
        final String form = html("/policies/contexts/edit?name=claims", "ADMIN");
        assertTrue(form.contains("name=\"originalScope\" value=\"document\""), form);
        assertTrue(form.contains("value=\"context\"") && form.contains("The whole context"), "the scope choice");

        mockMvc.perform(post("/policies/contexts/edit").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "claims").param("disambiguation", "true").param("scope", "context")
                        .param("originalDisambiguation", "true").param("originalScope", "document")
                        .param("originalLedger", "false"))
                .andExpect(redirectedUrl("/policies/contexts"));
        verify(contexts).update(KEY, "claims", null, "context", null, null);

        mockMvc.perform(post("/policies/contexts/edit").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "claims").param("disambiguation", "true").param("scope", "document")
                        .param("originalDisambiguation", "true").param("originalScope", "document")
                        .param("originalLedger", "false"))
                .andExpect(flash().attribute("success", "Context \"claims\" was not changed."));
        verify(contexts, never()).update(any(), any(), any(), eq("document"), any(), any());
    }

    @Test
    void clearingAndDeletingActAsTheOwnerChosen() throws Exception {
        mockMvc.perform(post("/policies/contexts/clear").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "claims").param("owner", "ws"))
                .andExpect(redirectedUrl("/policies/contexts?owner=ws"))
                .andExpect(flash().attribute("success", "Context \"claims\" cleared."));
        verify(contexts).clear(KEY, "claims", "ws");

        mockMvc.perform(post("/policies/contexts/delete").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "claims").param("owner", "ws"))
                .andExpect(flash().attribute("success", "Context \"claims\" deleted."));
        verify(contexts).delete(KEY, "claims", "ws");

        doThrow(new ClientException("Not Found", 404, null)).when(contexts).clear(anyString(), eq("x"), eq("ws"));
        mockMvc.perform(post("/policies/contexts/clear").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "x").param("owner", "ws"))
                .andExpect(flash().attribute("error", PhilterContextsController.CROSS_USER));
    }
}
