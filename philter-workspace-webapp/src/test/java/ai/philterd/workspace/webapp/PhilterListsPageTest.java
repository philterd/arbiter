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

import ai.philterd.philter.model.CustomList;
import ai.philterd.philter.model.CustomListSummary;
import ai.philterd.philter.model.Page;
import ai.philterd.philter.model.RedactList;
import ai.philterd.philter.model.RedactListTerms;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.workspace.webapp.controllers.PhilterListsController;
import ai.philterd.workspace.webapp.security.PhilterListsService;
import ai.philterd.workspace.webapp.security.SecurityConfig;
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
import static org.mockito.ArgumentMatchers.anyLong;
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

/** The Custom Lists and Always and Never Redact tabs on Philter's lists APIs, as the signed-in person. */
@WebMvcTest(controllers = PhilterListsController.class)
@Import(SecurityConfig.class)
class PhilterListsPageTest extends WebMvcSliceMocks {

    private static final String KEY = "enc-session-key";

    @Autowired private MockMvc mockMvc;
    @MockBean private PhilterListsService lists;

    private static CustomListSummary summary(final String name, final String owner) {
        final CustomListSummary list = new CustomListSummary();
        list.setName(name);
        list.setDescription("about " + name);
        list.setSize(3);
        list.setOwner(owner);
        return list;
    }

    private static RedactListTerms terms(final long revision, final String... terms) {
        final RedactListTerms list = new RedactListTerms();
        list.setTerms(List.of(terms));
        list.setRevision(revision);
        return list;
    }

    private String page(final String url, final String role) throws Exception {
        return mockMvc.perform(get(url).with(signedIn("boss", role))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theListIsThePersonsOwnListsWithTheirActions() throws Exception {
        when(lists.list(eq(KEY), isNull(), any())).thenReturn(new Page<>(List.of(summary("codenames", null)), 1));

        final String html = page("/policies/lists", "ADMIN");

        assertTrue(html.contains(">codenames<") && html.contains("about codenames"), html);
        assertTrue(html.contains("href=\"/policies/lists/edit?name=codenames\""), "Edit, with no owner parameter");
        assertTrue(html.contains("data-confirm-dialog=\"delete-0\""), "Delete is confirmed");
        assertTrue(html.contains("href=\"/policies/lists/new\""), "New list");
        assertTrue(html.contains("name=\"owner\""), "administrators get the owner filter");
        assertTrue(html.contains("href=\"/policies/redact-lists\""), "the tabs");
    }

    @Test
    void anAdministratorCanListAnotherUsersListsOrEveryones() throws Exception {
        when(lists.list(eq(KEY), eq("ws"), any())).thenReturn(new Page<>(List.of(summary("batch", "ws")), 1));
        final String one = page("/policies/lists?owner=ws", "ADMIN");
        assertTrue(one.contains("href=\"/policies/lists/edit?name=batch&amp;owner=ws\""), one);
        assertTrue(one.contains("href=\"/policies/lists/new?owner=ws\""));

        when(lists.listAcrossUsers(eq(KEY), any())).thenReturn(
                new Page<>(List.of(summary("a", "jdoe"), summary("b", "ann")), 2));
        final String all = page("/policies/lists?all=true", "ADMIN");
        assertTrue(all.contains(">Owner<") && all.contains(">jdoe<") && all.contains(">ann<"), all);
        assertTrue(all.contains("href=\"/policies/lists/edit?name=b&amp;owner=ann\""));
        assertFalse(all.contains("href=\"/policies/lists/new"), "a new list needs one owner");
    }

    @Test
    void reachingAnotherUserWithoutCrossUserAccessIsExplained() throws Exception {
        when(lists.list(eq(KEY), eq("ws"), any())).thenThrow(new ClientException("Not Found", 404, null));
        assertTrue(page("/policies/lists?owner=ws", "ADMIN").contains("ADMIN_CROSS_USER_ACCESS_ENABLED=true"));

        doThrow(new ClientException("Not Found", 404, null)).when(lists).delete(KEY, "batch", "ws");
        mockMvc.perform(post("/policies/lists/delete").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "batch").param("owner", "ws"))
                .andExpect(redirectedUrl("/policies/lists?owner=ws"))
                .andExpect(flash().attribute("error", PhilterListsController.CROSS_USER));
    }

    @Test
    void auditorsReadListsButCannotChooseAnOwnerOrChangeThem() throws Exception {
        when(lists.list(eq(KEY), isNull(), any())).thenReturn(new Page<>(List.of(summary("codenames", null)), 1));
        final String html = page("/policies/lists?owner=boss&all=true", "AUDITOR");
        verify(lists).list(eq(KEY), isNull(), any());
        verify(lists, never()).listAcrossUsers(any(), any());
        assertFalse(html.contains("name=\"owner\""), "no owner filter");
        assertFalse(html.contains("/policies/lists/new") || html.contains("data-confirm-dialog=\""), "read-only");
        assertTrue(html.contains(">View<"), html);

        final CustomList list = new CustomList();
        list.setItems(List.of("Project Phoenix"));
        list.setDescription("");
        when(lists.get(KEY, "codenames", null)).thenReturn(list);
        final String form = page("/policies/lists/edit?name=codenames", "AUDITOR");
        assertTrue(form.contains("readonly") && !form.contains(">Save<"), form);
        final String blank = page("/policies/lists/new", "AUDITOR");
        assertFalse(blank.contains(">Save<"), "no save an auditor cannot make");

        mockMvc.perform(post("/policies/lists/new").with(signedIn("boss", "AUDITOR")).with(csrf())
                        .param("name", "x").param("items", "y"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/policies/redact-lists").with(signedIn("boss", "AUDITOR")).with(csrf())
                        .param("list", "always").param("terms", "y").param("revision", "0"))
                .andExpect(status().isForbidden());
    }

    @Test
    void creatingSendsTheTrimmedItemsWithoutBlankLines() throws Exception {
        mockMvc.perform(post("/policies/lists/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", " codenames ").param("description", " ")
                        .param("items", "Project Phoenix\r\n\r\n  Bluebird \r\n").param("owner", "ws"))
                .andExpect(redirectedUrl("/policies/lists?owner=ws"))
                .andExpect(flash().attribute("success", "List \"codenames\" created."));
        verify(lists).create(KEY, "codenames", null, List.of("Project Phoenix", "Bluebird"), "ws");
    }

    @Test
    void aListPhilterWouldRefuseIsNotSentAndTheProblemIsNamedWithoutRepeatingTheItem() throws Exception {
        final String badName = mockMvc.perform(post("/policies/lists/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "a/b").param("items", "x"))
                .andExpect(view().name("list-form")).andReturn().getResponse().getContentAsString();
        assertTrue(badName.contains("A list name cannot contain"), badName);

        final String longItem = "x".repeat(51);
        final String tooLong = mockMvc.perform(post("/policies/lists/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "n").param("items", "ok\n" + longItem))
                .andExpect(view().name("list-form")).andReturn().getResponse().getContentAsString();
        assertTrue(tooLong.contains("Item 2 is longer than 50 characters."), tooLong);
        assertTrue(tooLong.contains("id=\"items-error\""), "on the field");

        final String many = mockMvc.perform(post("/policies/lists/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "n").param("items", "x\n".repeat(101)))
                .andExpect(view().name("list-form")).andReturn().getResponse().getContentAsString();
        assertTrue(many.contains("at most 100 items; this one has 101"), many);

        final String none = mockMvc.perform(post("/policies/lists/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "n").param("items", "\n \n"))
                .andExpect(view().name("list-form")).andReturn().getResponse().getContentAsString();
        assertTrue(none.contains("Enter at least one item."), none);
        verifyNoInteractions(lists);
    }

    @Test
    void aNameAlreadyInUseIsExplainedOnTheForm() throws Exception {
        doThrow(new ClientException("Conflict", 409, "exists", "list_exists"))
                .when(lists).create(any(), any(), any(), any(), any());
        final String html = mockMvc.perform(post("/policies/lists/new").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "codenames").param("items", "Bluebird"))
                .andExpect(view().name("list-form")).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("A list with that name already exists."), html);
        assertTrue(html.contains("Bluebird"), "the items are kept");
    }

    @Test
    void editingShowsTheItemsAndSendsTheDescriptionOnlyWhenItChanged() throws Exception {
        final CustomList list = new CustomList();
        list.setItems(List.of("Project Phoenix", "Bluebird"));
        list.setDescription("code names");
        when(lists.get(KEY, "codenames", null)).thenReturn(list);
        final String form = page("/policies/lists/edit?name=codenames", "ADMIN");
        assertTrue(form.contains("Project Phoenix\nBluebird"), form);
        assertTrue(form.contains("list:codenames"), "how policies refer to it");

        mockMvc.perform(post("/policies/lists/edit").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "codenames").param("description", "code names")
                        .param("originalDescription", "code names").param("items", "Bluebird"))
                .andExpect(redirectedUrl("/policies/lists"));
        verify(lists).replace(KEY, "codenames", null, List.of("Bluebird"), null);

        mockMvc.perform(post("/policies/lists/edit").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "codenames").param("description", "")
                        .param("originalDescription", "code names").param("items", "Bluebird"))
                .andExpect(redirectedUrl("/policies/lists"));
        verify(lists).replace(KEY, "codenames", "", List.of("Bluebird"), null);
    }

    @Test
    void deletingWarnsThatPoliciesReferringToTheListFail() throws Exception {
        when(lists.list(eq(KEY), isNull(), any())).thenReturn(new Page<>(List.of(summary("codenames", null)), 1));
        assertTrue(page("/policies/lists", "ADMIN").contains("refers to it as list:codenames fails"));

        mockMvc.perform(post("/policies/lists/delete").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("name", "codenames"))
                .andExpect(redirectedUrl("/policies/lists"))
                .andExpect(flash().attribute("success", "List \"codenames\" deleted."));
        verify(lists).delete(KEY, "codenames", null);
    }

    @Test
    void theRedactListsShowBothListsAndTheirRevisions() throws Exception {
        when(lists.getRedactList(KEY, RedactList.ALWAYS, null)).thenReturn(terms(3, "Project Phoenix", "Bluebird"));
        when(lists.getRedactList(KEY, RedactList.NEVER, null)).thenReturn(terms(0, "Acme"));

        final String html = page("/policies/redact-lists", "ADMIN");

        assertTrue(html.contains("Project Phoenix\nBluebird"), html);
        assertTrue(html.contains("name=\"revision\" value=\"3\"") && html.contains("name=\"revision\" value=\"0\""));
        assertTrue(html.contains("Save Always redact list") && html.contains("Save Never redact list"));
        assertTrue(html.contains("2 terms, revision 3.") && html.contains("1 term, revision 0."), "counts");
        assertTrue(html.contains("name=\"owner\""), "administrators get the owner filter");
        assertFalse(html.contains("Every user&#39;s") || html.contains("name=\"all\""),
                "Philter reads redact lists one owner at a time");
    }

    @Test
    void anAdministratorReadsAndSavesAnotherUsersRedactLists() throws Exception {
        when(lists.getRedactList(KEY, RedactList.ALWAYS, "ws")).thenReturn(terms(1, "a"));
        when(lists.getRedactList(KEY, RedactList.NEVER, "ws")).thenReturn(terms(2, "b"));
        final String html = page("/policies/redact-lists?owner=ws", "ADMIN");
        assertTrue(html.contains("name=\"owner\" value=\"ws\""), html);

        mockMvc.perform(post("/policies/redact-lists").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("list", "never").param("terms", "b\r\nc").param("revision", "2").param("owner", "ws"))
                .andExpect(redirectedUrl("/policies/redact-lists?owner=ws"))
                .andExpect(flash().attribute("success", "Never redact list saved."));
        verify(lists).replaceRedactList(KEY, RedactList.NEVER, List.of("b", "c"), 2L, "ws");
    }

    @Test
    void anAuditorsOwnerIsIgnoredOnTheRedactLists() throws Exception {
        when(lists.getRedactList(eq(KEY), any(), isNull())).thenReturn(terms(0));
        final String html = page("/policies/redact-lists?owner=ws", "AUDITOR");
        verify(lists, never()).getRedactList(any(), any(), eq("ws"));
        assertFalse(html.contains("name=\"owner\"") || html.contains(">Save "), html);
    }

    @Test
    void aSaveAfterSomeoneElsesChangeShowsTheCurrentListAndKeepsTheEdits() throws Exception {
        doThrow(new ClientException("Conflict", 409, "changed", "redact_list_changed"))
                .when(lists).replaceRedactList(any(), eq(RedactList.ALWAYS), any(), anyLong(), any());
        when(lists.getRedactList(KEY, RedactList.ALWAYS, null)).thenReturn(terms(4, "Theirs"));
        when(lists.getRedactList(KEY, RedactList.NEVER, null)).thenReturn(terms(0));

        final String html = mockMvc.perform(post("/policies/redact-lists").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("list", "always").param("terms", "Mine").param("revision", "3"))
                .andExpect(view().name("redact-lists")).andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("Someone changed this list after you opened it"), html);
        assertTrue(html.contains(">Theirs</textarea>") && html.contains("name=\"revision\" value=\"4\""),
                "Philter's current list, at its new revision");
        assertTrue(html.contains("Your unsaved edits") && html.contains(">Mine</textarea>"), "the edits are kept");
    }

    @Test
    void aRedactListPhilterWouldRefuseIsNotSentAndKeepsTheEdits() throws Exception {
        when(lists.getRedactList(KEY, RedactList.ALWAYS, null)).thenReturn(terms(3, "old"));
        when(lists.getRedactList(KEY, RedactList.NEVER, null)).thenReturn(terms(0));

        final String html = mockMvc.perform(post("/policies/redact-lists").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("list", "always").param("terms", "ok\n" + "y".repeat(101)).param("revision", "3"))
                .andExpect(view().name("redact-lists")).andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("Term 2 is longer than 100 characters."), html);
        assertTrue(html.contains("ok\n" + "y".repeat(101)) && html.contains("name=\"revision\" value=\"3\""));
        verify(lists, never()).replaceRedactList(any(), any(), any(), anyLong(), any());
    }

    @Test
    void savingAnEmptyRedactListClearsIt() throws Exception {
        mockMvc.perform(post("/policies/redact-lists").with(signedIn("boss", "ADMIN")).with(csrf())
                        .param("list", "always").param("terms", "  \n").param("revision", "5"))
                .andExpect(redirectedUrl("/policies/redact-lists"));
        verify(lists).replaceRedactList(KEY, RedactList.ALWAYS, List.of(), 5L, null);
    }
}
