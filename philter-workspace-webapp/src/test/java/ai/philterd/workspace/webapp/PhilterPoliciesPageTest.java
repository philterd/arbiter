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

import ai.philterd.philter.PhilterClient;
import ai.philterd.philter.model.DeletedPolicy;
import ai.philterd.philter.model.GetDeletedPoliciesResponse;
import ai.philterd.philter.model.ListOptions;
import ai.philterd.philter.model.Page;
import ai.philterd.philter.model.PolicyDetails;
import ai.philterd.philter.model.PolicyDiff;
import ai.philterd.philter.model.PolicyRollbackResponse;
import ai.philterd.philter.model.PolicySummary;
import ai.philterd.philter.model.PolicyVersionSummary;
import ai.philterd.philter.model.PolicyWithETag;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.workspace.model.GeneralSettings;
import ai.philterd.workspace.service.GeneralSettingsService;
import ai.philterd.workspace.webapp.controllers.PhilterPoliciesController;
import ai.philterd.workspace.webapp.controllers.PolicyController;
import ai.philterd.workspace.webapp.security.PhilterPoliciesService;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

/** The Policies page on Philter's Policies API, as the signed-in person. */
@WebMvcTest(controllers = {PhilterPoliciesController.class, PolicyController.class})
@Import(SecurityConfig.class)
class PhilterPoliciesPageTest extends WebMvcSliceMocks {

    private static final String KEY = "enc-session-key";

    @Autowired private MockMvc mockMvc;
    @MockBean private PhilterPoliciesService policies;
    @MockBean private GeneralSettingsService generalSettingsService;
    @MockBean private ai.philterd.workspace.service.AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        when(generalSettingsService.load()).thenReturn(new GeneralSettings());
    }

    private static PolicySummary summary(final String name, final String owner) {
        final PolicySummary policy = new PolicySummary();
        policy.setName(name);
        policy.setDescription("about " + name);
        policy.setCreatedAt("2026-10-01T10:00:00Z");
        policy.setUpdatedAt("2026-10-02T10:00:00Z");
        policy.setOwner(owner);
        return policy;
    }

    private String page(final String url, final String role) throws Exception {
        return mockMvc.perform(get(url).with(signedIn("boss", role))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theListIsThePersonsOwnPoliciesWithTheirActions() throws Exception {
        when(policies.list(eq(KEY), isNull(), any())).thenReturn(new Page<>(List.of(summary("claims", null)), 1));

        final String html = page("/policies", "ADMIN");

        assertTrue(html.contains(">claims<") && html.contains("about claims"), html);
        assertTrue(html.contains("2026-10-01T10:00:00Z"), "created");
        assertTrue(html.contains("href=\"/policies/edit?name=claims\""), "Edit, with no owner parameter");
        assertTrue(html.contains("href=\"/policies/copy?name=claims\""));
        assertTrue(html.contains("href=\"/policies/history?name=claims\""));
        assertTrue(html.contains("data-confirm-dialog=\"delete-0\""), "Delete is confirmed");
        assertTrue(html.contains("href=\"/policies/new\""), "New policy");
        assertTrue(html.contains("name=\"owner\""), "administrators get the owner filter");
    }

    @Test
    void anAdministratorCanListAnotherUsersPoliciesOrEveryonesAndActOnThem() throws Exception {
        when(policies.list(eq(KEY), eq("ws"), any())).thenReturn(new Page<>(List.of(summary("batch", "ws")), 1));
        final String one = page("/policies?owner=ws", "ADMIN");
        assertTrue(one.contains("href=\"/policies/edit?name=batch&amp;owner=ws\""), one);
        assertTrue(one.contains("name=\"owner\" value=\"ws\""), "the delete confirmation posts the owner");

        when(policies.listAcrossUsers(eq(KEY), any())).thenReturn(
                new Page<>(List.of(summary("a", "jdoe"), summary("b", "ann")), 2));
        final String all = page("/policies?all=true", "ADMIN");
        assertTrue(all.contains(">Owner<") && all.contains(">jdoe<") && all.contains(">ann<"), all);
        assertTrue(all.contains("href=\"/policies/edit?name=b&amp;owner=ann\""));
        assertFalse(all.contains("href=\"/policies/new"), "a new policy needs one owner");
    }

    @Test
    void othersCannotChooseAnOwnerOrChangePolicies() throws Exception {
        when(policies.list(eq(KEY), isNull(), any())).thenReturn(new Page<>(List.of(summary("claims", null)), 1));

        final String html = page("/policies?owner=boss&all=true", "AUDITOR");

        verify(policies).list(eq(KEY), isNull(), any());
        verify(policies, never()).listAcrossUsers(any(), any());
        assertFalse(html.contains("name=\"owner\""), "no owner filter");
        assertFalse(html.contains("/policies/edit") || html.contains("data-confirm-dialog=\""), "read-only");
        assertTrue(html.contains("href=\"/policies/view?name=claims\""), "but can view");
    }

    @Test
    void reachingAnotherUserWithoutCrossUserAccessIsExplained() throws Exception {
        when(policies.list(eq(KEY), eq("ws"), any())).thenThrow(new ClientException("Not Found", 404, null));

        assertTrue(page("/policies?owner=ws", "ADMIN").contains("ADMIN_CROSS_USER_ACCESS_ENABLED=true"));
    }

    @Test
    void managedDeletedAndBatchPoliciesHaveTheirOwnLists() throws Exception {
        when(policies.listManaged(eq(KEY), any())).thenReturn(new Page<>(List.of(summary("managed_hipaa", null)), 1));
        final String managed = page("/policies/managed", "ADMIN");
        assertTrue(managed.contains("href=\"/policies/copy?name=managed_hipaa\""), managed);
        assertFalse(managed.contains("/policies/edit"), "managed policies cannot be changed");

        final DeletedPolicy gone = new DeletedPolicy();
        gone.setName("old");
        gone.setLatestRevision(4);
        final GetDeletedPoliciesResponse deleted = new GetDeletedPoliciesResponse();
        deleted.setPolicies(List.of(gone));
        deleted.setTotal(1);
        when(policies.listDeleted(eq(KEY), isNull(), any())).thenReturn(deleted);
        final String deletedPage = page("/policies/deleted", "ADMIN");
        assertTrue(deletedPage.contains("href=\"/policies/history?name=old&amp;deleted=true\""), deletedPage);
        assertTrue(deletedPage.contains("Restore old from revision 4?"));

        final PhilterClient background = org.mockito.Mockito.mock(PhilterClient.class);
        when(philterSettings.client()).thenReturn(background);
        when(background.listPolicies(isNull(), any(ListOptions.class)))
                .thenReturn(new Page<>(List.of(summary("redact-claims", null)), 1));
        final String batches = page("/policies/batches", "ADMIN");
        assertTrue(batches.contains(">redact-claims<") && batches.contains("batches=true"), batches);
    }

    @Test
    void editingSendsOnlyWhatChangedWithTheTagItRead() throws Exception {
        when(policies.getWithETag(KEY, "claims", null)).thenReturn(new PolicyWithETag("{\"identifiers\":{}}", "\"t1\""));
        final PolicyDetails details = new PolicyDetails();
        details.setDescription("old description");
        details.setNotes("notes");
        when(policies.details(KEY, "claims", null)).thenReturn(details);

        final String form = page("/policies/edit?name=claims", "ADMIN");
        assertTrue(form.contains("name=\"eTag\" value=\"&quot;t1&quot;\""), form);
        assertTrue(form.contains("&quot;identifiers&quot; : { }"), "pretty JSON");

        mockMvc.perform(post("/policies/edit").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "claims").param("json", "{\"identifiers\":{\"ssn\":{}}}")
                        .param("description", "new description").param("notes", "notes")
                        .param("originalDescription", "old description").param("originalNotes", "notes")
                        .param("eTag", "\"t1\""))
                .andExpect(redirectedUrl("/policies"));
        verify(policies).replace(KEY, "claims", "{\"identifiers\":{\"ssn\":{}}}", "new description", null, "\"t1\"", null);
    }

    @Test
    void aSaveAfterSomeoneElsesChangeIsRefusedAndKeepsTheEdit() throws Exception {
        doThrow(new ClientException("Conflict", 409, "changed", "policy_changed"))
                .when(policies).replace(any(), any(), any(), any(), any(), any(), any());

        final String html = mockMvc.perform(post("/policies/edit").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "claims").param("json", "{\"mine\":true}").param("eTag", "\"t1\""))
                .andExpect(view().name("policy-form")).andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("This was changed by someone else after you opened it."), html);
        assertTrue(html.contains("{&quot;mine&quot;:true}"), "the edit is kept");
    }

    @Test
    void creatingChecksTheJsonThenShowsPhiltersReasonForAnInvalidPolicy() throws Exception {
        mockMvc.perform(post("/policies/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "claims").param("json", "{not json"))
                .andExpect(view().name("policy-form"));
        verifyNoInteractions(policies);

        doThrow(new ClientException("Bad Request", 400,
                "The policy must contain an 'identifiers' object describing the information to redact.",
                "invalid_request")).when(policies).create(any(), any(), any(), any(), any(), any());
        final String html = mockMvc.perform(post("/policies/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "claims").param("json", "{}"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Philter did not accept the policy: The policy must contain an &#39;identifiers&#39;"), html);

        doThrow(new ClientException("Conflict", 409, null, "policy_exists"))
                .when(policies).create(any(), any(), any(), any(), any(), any());
        assertTrue(mockMvc.perform(post("/policies/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "claims").param("json", "{\"identifiers\":{}}"))
                .andReturn().getResponse().getContentAsString().contains("A policy with that name already exists."));
    }

    @Test
    void aNewPolicyStartsFromPhiltersTemplate() throws Exception {
        when(policies.template(KEY, "default")).thenReturn("{\"identifiers\":{\"ssn\":{}}}");
        assertTrue(page("/policies/new", "ADMIN").contains("&quot;ssn&quot;"));

        when(policies.template(KEY, "default")).thenThrow(new ClientException("Not Found", 404, null));
        assertTrue(page("/policies/new", "ADMIN").contains("template could not be read"));
    }

    @Test
    void deletingIsConfirmedAndPhiltersRefusalIsReported() throws Exception {
        assertTrue(page("/policies/delete?name=claims", "ADMIN").contains("Delete the policy claims?"));

        mockMvc.perform(post("/policies/delete").with(signedIn("boss", "ADMIN")).with(csrf()).param("name", "claims"))
                .andExpect(redirectedUrl("/policies"));
        verify(policies).delete(KEY, "claims", null);

        doThrow(new ClientException("Conflict", 409, null, "policy_default")).when(policies).delete(any(), any(), any());
        mockMvc.perform(post("/policies/delete").with(signedIn("boss", "ADMIN")).with(csrf()).param("name", "default"))
                .andExpect(flash().attribute("error", "The default policy cannot be deleted."));
    }

    @Test
    void historyOffersRollbackForOlderRevisionsAndComparesTwo() throws Exception {
        final PolicyDetails details = new PolicyDetails();
        details.setEtag("\"t3\"");
        when(policies.details(KEY, "claims", null)).thenReturn(details);
        when(policies.versions(KEY, "claims", null)).thenReturn(new Page<>(List.of(version(3, "boss"),
                version(2, "jdoe"), version(1, "jdoe")), 3));
        final PolicyDiff diff = new PolicyDiff();
        diff.setFrom(2);
        diff.setTo(3);
        final PolicyDiff.Change change = new PolicyDiff.Change();
        change.setOp("replace");
        change.setPath("/identifiers/ssn/enabled");
        change.setOldValue(new JsonPrimitive(false));
        change.setValue(new JsonPrimitive(true));
        diff.setChanges(List.of(change));
        when(policies.diff(KEY, "claims", 2, 3, null)).thenReturn(diff);

        final String html = page("/policies/history?name=claims&from=2&to=3", "ADMIN");

        assertTrue(html.contains("Roll claims back to revision 2?") && html.contains("Roll claims back to revision 1?"), html);
        assertFalse(html.contains("Roll claims back to revision 3?"), "not to the current revision");
        assertTrue(html.contains("name=\"eTag\" value=\"&quot;t3&quot;\""), "rollback is refused if it changed since");
        assertTrue(html.contains("/identifiers/ssn/enabled") && html.contains(">false<") && html.contains(">true<"));

        when(policies.rollback(KEY, "claims", 2, "\"t3\"", null)).thenReturn(new PolicyRollbackResponse());
        mockMvc.perform(post("/policies/rollback").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "claims").param("revision", "2").param("eTag", "\"t3\""))
                .andExpect(redirectedUrl("/policies/history?name=claims"));
        verify(policies).rollback(KEY, "claims", 2, "\"t3\"", null);
    }

    @Test
    void aDeletedPolicyIsRestoredFromARevision() throws Exception {
        mockMvc.perform(post("/policies/restore").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "old").param("revision", "4"))
                .andExpect(redirectedUrl("/policies"));
        verify(policies).restore(KEY, "old", 4, null);
    }

    @Test
    void auditorsCannotChangePolicies() throws Exception {
        mockMvc.perform(post("/policies/delete").with(signedIn("ann", "AUDITOR")).with(csrf()).param("name", "claims"))
                .andExpect(status().isForbidden());
        verify(policies, never()).delete(anyString(), anyString(), any());
    }

    @Test
    void theDeletedListOffersOneOwnerNotEveryone() throws Exception {
        final GetDeletedPoliciesResponse none = new GetDeletedPoliciesResponse();
        none.setPolicies(List.of());
        when(policies.listDeleted(eq(KEY), eq("ws"), any())).thenReturn(none);

        final String html = page("/policies/deleted?owner=ws&all=true", "ADMIN");

        verify(policies).listDeleted(eq(KEY), eq("ws"), any());
        assertTrue(html.contains("name=\"owner\""), "one owner can be chosen");
        assertFalse(html.contains("name=\"all\""), "Philter lists deleted policies one owner at a time");
    }

    @Test
    void aRejectedBackgroundKeyIsNamedOnTheBatchesList() throws Exception {
        final PhilterClient background = org.mockito.Mockito.mock(PhilterClient.class);
        when(philterSettings.client()).thenReturn(background);
        when(background.listPolicies(isNull(), any(ListOptions.class)))
                .thenThrow(new ai.philterd.philter.model.exceptions.UnauthorizedException("no"));

        final String html = page("/policies/batches", "ADMIN");

        assertTrue(html.contains("Philter rejected PHILTER_WORKSPACE_PHILTER_API_KEY"), html);
    }

    @Test
    void notesUnchangedButForBrowserLineBreaksAreNotSent() throws Exception {
        mockMvc.perform(post("/policies/edit").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "claims").param("json", "{\"identifiers\":{}}").param("eTag", "\"t1\"")
                        .param("notes", "line one\r\nline two").param("originalNotes", "line one\nline two"))
                .andExpect(redirectedUrl("/policies"));
        verify(policies).replace(KEY, "claims", "{\"identifiers\":{}}", null, null, "\"t1\"", null);
    }

    @Test
    void actingOnAnotherUsersPolicyWithoutCrossUserAccessIsExplained() throws Exception {
        doThrow(new ClientException("Not Found", 404, null)).when(policies).delete(any(), any(), any());
        mockMvc.perform(post("/policies/delete").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "claims").param("owner", "ws"))
                .andExpect(redirectedUrl("/policies?owner=ws"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString(
                        "ADMIN_CROSS_USER_ACCESS_ENABLED=true")));
    }

    @Test
    void theFinalizationTabHasItsOwnPath() throws Exception {
        assertTrue(page("/policies/finalization", "ADMIN").contains("Add finalization policy"));
        mockMvc.perform(get("/policies?tab=finalization").with(signedIn("boss", "ADMIN")))
                .andExpect(redirectedUrl("/policies/finalization"));
    }

    private static PolicyVersionSummary version(final int revision, final String author) {
        final PolicyVersionSummary version = new PolicyVersionSummary();
        version.setRevision(revision);
        version.setAuthor(author);
        version.setCapturedTimestamp("2026-10-0" + revision + "T10:00:00Z");
        return version;
    }
}
