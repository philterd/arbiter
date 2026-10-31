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

import ai.philterd.philter.model.LegalHoldRequest;
import ai.philterd.philter.model.LegalHoldResponse;
import ai.philterd.philter.model.OwnedLegalHoldResponse;
import ai.philterd.philter.model.Page;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.webapp.controllers.PhilterHoldsController;
import ai.philterd.workspace.webapp.security.PhilterHoldsService;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

/** Admin, Legal Holds on Philter's legal holds API, as the signed-in person. */
@WebMvcTest(controllers = PhilterHoldsController.class)
@Import(SecurityConfig.class)
class PhilterHoldsPageTest extends WebMvcSliceMocks {

    private static final String KEY = "enc-session-key";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PhilterHoldsService holds;
    @MockitoBean private AuditLogService auditLogService;

    private static <T extends LegalHoldResponse> T hold(final T hold, final String reference, final String scope,
                                                        final String value) {
        hold.setReference(reference);
        hold.setScopeType(scope);
        hold.setScopeValue(value);
        hold.setReason("Matter 2026-17");
        hold.setSetAt("2026-10-20T10:00:00Z");
        return hold;
    }

    private String html(final String url, final String role) throws Exception {
        return mockMvc.perform(get(url).with(signedIn("boss", role))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theListShowsEachHoldsScopeReferenceReasonAndWhenItWasPlaced() throws Exception {
        when(holds.list(eq(KEY), isNull(), any())).thenReturn(new Page<>(List.of(
                hold(new LegalHoldResponse(), "matter-17", "document_chain", "doc-123"),
                hold(new LegalHoldResponse(), "all-of-it", "user", "boss")), 2));

        final String html = html("/admin/holds", "ADMIN");

        assertTrue(html.contains(">matter-17<") && html.contains("One document&#39;s ledger chain")
                && html.contains(">doc-123<"), html);
        assertTrue(html.contains("All of the user&#39;s evidence") && html.contains(">Matter 2026-17<"));
        assertTrue(html.contains(">2026-10-20T10:00:00Z<"), "when");
        assertTrue(html.contains("Philter does not report who placed a hold."), "the gap is stated");
        assertTrue(html.contains("Keep original"), "told apart from the finalization option");
        assertTrue(html.contains("data-confirm-dialog=\"release-0\"") && html.contains("href=\"/admin/holds/new\""));
        assertTrue(html.contains("aria-current=\"page\"") && html.contains(">Legal Holds<"), "its Admin tab");
    }

    @Test
    void theListIsNewestFirstUnlessSorted() throws Exception {
        when(holds.list(eq(KEY), isNull(), any())).thenReturn(new Page<>(List.of(), 0));
        html("/admin/holds", "ADMIN");
        verify(holds).list(eq(KEY), isNull(), org.mockito.ArgumentMatchers.argThat(o -> o.getSort() == null));
    }

    @Test
    void anAdministratorCanListEveryUsersHolds() throws Exception {
        final OwnedLegalHoldResponse owned = hold(new OwnedLegalHoldResponse(), "matter-17", "user", "philter-workspace");
        owned.setOwner("philter-workspace");
        when(holds.listAcrossUsers(eq(KEY), any())).thenReturn(new Page<>(List.of(owned), 1));

        final String html = html("/admin/holds?all=true", "ADMIN");

        assertTrue(html.contains(">Owner<") && html.contains(">philter-workspace<"), html);
        assertTrue(html.contains("name=\"owner\" value=\"philter-workspace\""), "release acts as that owner");
        assertFalse(html.contains("href=\"/admin/holds/new"), "a hold is placed for one owner");
    }

    @Test
    void reachingAnotherUserWithoutCrossUserAccessIsExplained() throws Exception {
        when(holds.list(eq(KEY), eq("ws"), any())).thenThrow(new ClientException("Not Found", 404, null));
        assertTrue(html("/admin/holds?owner=ws", "ADMIN").contains("ADMIN_CROSS_USER_ACCESS_ENABLED=true"));
    }

    @Test
    void auditorsReadHoldsButCannotPlaceOrReleaseThem() throws Exception {
        when(holds.list(eq(KEY), isNull(), any())).thenReturn(new Page<>(List.of(
                hold(new LegalHoldResponse(), "matter-17", "user", "boss")), 1));
        final String html = html("/admin/holds", "AUDITOR");
        assertFalse(html.contains("data-confirm-dialog=\"") || html.contains("/admin/holds/new"), html);
        mockMvc.perform(post("/admin/holds/release").with(signedIn("boss", "AUDITOR")).with(csrf())
                .param("reference", "matter-17")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/holds/new").with(signedIn("boss", "AUDITOR")))
                .andExpect(redirectedUrl("/admin/holds"));
    }

    @Test
    void placingAHoldOnADocumentSendsItsId() throws Exception {
        mockMvc.perform(post("/admin/holds/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("reference", " matter-17 ").param("scope", "document_chain")
                        .param("documentId", " doc-123 ").param("reason", "Matter 2026-17").param("owner", "ws"))
                .andExpect(redirectedUrl("/admin/holds?owner=ws"))
                .andExpect(flash().attribute("success", "Hold \"matter-17\" placed."));
        final ArgumentCaptor<LegalHoldRequest> sent = ArgumentCaptor.forClass(LegalHoldRequest.class);
        verify(holds).place(eq(KEY), sent.capture(), eq("ws"));
        assertEquals("matter-17", sent.getValue().getReference());
        assertEquals("document_chain", sent.getValue().getScopeType());
        assertEquals("doc-123", sent.getValue().getScopeValue());
        assertEquals("Matter 2026-17", sent.getValue().getReason());
        verify(auditLogService, never()).log(eq("LEGAL_HOLD_SET"), any(), any(), any());
    }

    @Test
    void aUserHoldLeavesTheScopeValueToPhilter() throws Exception {
        mockMvc.perform(post("/admin/holds/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("reference", "everything").param("scope", "user").param("documentId", "ignored"))
                .andExpect(redirectedUrl("/admin/holds"));
        final ArgumentCaptor<LegalHoldRequest> sent = ArgumentCaptor.forClass(LegalHoldRequest.class);
        verify(holds).place(eq(KEY), sent.capture(), isNull());
        assertEquals("user", sent.getValue().getScopeType());
        assertNull(sent.getValue().getScopeValue());
        assertNull(sent.getValue().getReason(), "no reason");
    }

    @Test
    void aHoldPhilterWouldRefuseIsNotSent() throws Exception {
        final String noDocument = mockMvc.perform(post("/admin/holds/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("reference", "matter-17").param("scope", "document_chain"))
                .andExpect(view().name("admin-hold-form")).andReturn().getResponse().getContentAsString();
        assertTrue(noDocument.contains("Enter the ID of the document"), noDocument);

        final String badReference = mockMvc.perform(post("/admin/holds/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("reference", "a/b").param("scope", "user"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(badReference.contains("A hold reference cannot contain"), badReference);
        verifyNoInteractions(holds);
    }

    @Test
    void philtersRefusalsAreExplainedByTheirReason() throws Exception {
        doThrow(new ClientException("Conflict", 409, "exists", "hold_exists")).when(holds).place(any(), any(), any());
        final String exists = mockMvc.perform(post("/admin/holds/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("reference", "matter-17").param("scope", "user"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(exists.contains("A hold with that reference already exists."), exists);
        verify(auditLogService, never()).log(any(), any(), any(), any());

        doThrow(new ClientException("Forbidden", 403, "no", "admin_required")).when(holds).release(KEY, "theirs", null);
        mockMvc.perform(post("/admin/holds/release").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("reference", "theirs"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.startsWith(
                        "Only the person who placed this hold")));
    }

    @Test
    void aHoldThatIsAlreadyGoneIsExplained() throws Exception {
        doThrow(new ClientException("Not Found", 404, null)).when(holds).release(KEY, "gone", null);
        mockMvc.perform(post("/admin/holds/release").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("reference", "gone"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.startsWith("Philter has no hold with that reference.")));

        doThrow(new ClientException("Not Found", 404, null)).when(holds).release(KEY, "gone", "ws");
        mockMvc.perform(post("/admin/holds/release").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("reference", "gone").param("owner", "ws"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("released already"),
                        org.hamcrest.Matchers.containsString("ADMIN_CROSS_USER_ACCESS_ENABLED=true"))));
    }

    @Test
    void releasingAHoldActsAsTheOwnerChosen() throws Exception {
        mockMvc.perform(post("/admin/holds/release").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("reference", "matter-17").param("owner", "ws"))
                .andExpect(redirectedUrl("/admin/holds?owner=ws"))
                .andExpect(flash().attribute("success", "Hold \"matter-17\" released."));
        verify(holds).release(KEY, "matter-17", "ws");
        verify(auditLogService, never()).log(eq("LEGAL_HOLD_RELEASE"), any(), any(), any());
    }
}
