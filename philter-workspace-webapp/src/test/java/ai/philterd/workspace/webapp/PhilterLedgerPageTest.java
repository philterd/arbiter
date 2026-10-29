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

import ai.philterd.philter.model.GetLedgerResponse;
import ai.philterd.philter.model.LedgerChain;
import ai.philterd.philter.model.LedgerExport;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.webapp.controllers.PhilterLedgerController;
import ai.philterd.workspace.webapp.security.PhilterLedgerService;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin, Ledgers on Philter's redaction ledger API, as the signed-in person. */
@WebMvcTest(controllers = PhilterLedgerController.class)
@Import(SecurityConfig.class)
class PhilterLedgerPageTest extends WebMvcSliceMocks {

    private static final String KEY = "enc-session-key";
    private static final Gson GSON = new Gson();

    @Autowired private MockMvc mockMvc;
    @MockBean private PhilterLedgerService ledger;
    @MockBean private AuditLogService auditLogService;

    private static GetLedgerResponse chains(final String json) {
        return GSON.fromJson("{'chains':[" + json + "],'total':1}", GetLedgerResponse.class);
    }

    private static LedgerChain chain(final String fields) {
        return GSON.fromJson("{'documentId':'doc-1','total':2,'entries':["
                + "{'documentId':'doc-1','type':'','previousHash':'[genesis]','hash':'aaaaaaaaaaaaaaaa','policyName':'claims',"
                + "'policyVersion':3,'timestamp':'2026-10-20T10:00:00Z'},"
                + "{'documentId':'doc-1','type':'email-address','replacement':'{{{REDACTED-email-address}}}',"
                + "'startPosition':9,'previousHash':'aaaaaaaaaaaaaaaa','hash':'bbbbbbbbbbbbbbbb','policyName':'claims',"
                + "'policyVersion':3}]," + fields + "}", LedgerChain.class);
    }

    private String html(final String url, final String role) throws Exception {
        return mockMvc.perform(get(url).with(signedIn("boss", role))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theListShowsEachChainAndCanBeSearched() throws Exception {
        when(ledger.chains(eq(KEY), isNull(), any())).thenReturn(chains(
                "{'documentId':'doc-1','filename':'claim.txt','timestamp':'2026-10-20T10:00:00Z'}"));

        final String html = html("/admin/ledgers?q=claim", "ADMIN");

        assertTrue(html.contains(">doc-1<") && html.contains(">claim.txt<"), html);
        assertTrue(html.contains("href=\"/admin/ledgers/view?document=doc-1\""));
        assertTrue(html.contains("data-confirm-dialog=\"delete-0\""), "Delete is confirmed");
        assertTrue(html.contains("aria-current=\"page\"") && html.contains(">Ledgers<"), "its Admin tab");
        verify(ledger).chains(eq(KEY), isNull(), org.mockito.ArgumentMatchers.argThat(o -> "claim".equals(o.getSearch())));
    }

    @Test
    void everyUsersChainsAreListedWithoutTheSearchPhilterRefusesThere() throws Exception {
        when(ledger.chainsAcrossUsers(eq(KEY), any())).thenReturn(chains(
                "{'documentId':'doc-9','owner':'philter-workspace','timestamp':'2026-10-20T10:00:00Z'}"));

        final String html = html("/admin/ledgers?all=true&q=x", "ADMIN");

        assertTrue(html.contains(">philter-workspace<") && html.contains(">Owner<"), html);
        assertTrue(html.contains("href=\"/admin/ledgers/view?document=doc-9&amp;owner=philter-workspace\""));
        verify(ledger).chainsAcrossUsers(eq(KEY), org.mockito.ArgumentMatchers.argThat(o -> o.getSearch() == null));
    }

    @Test
    void aChainShowsItsVerificationAndAPageOfEntriesWithoutOriginalValues() throws Exception {
        when(ledger.chain(KEY, "doc-1", null, 0, 25)).thenReturn(chain(
                "'valid':true,'hashChainValid':true,'signaturesValid':true,'signedEntries':2,'unsignedEntries':0"));

        final String html = html("/admin/ledgers/view?document=doc-1", "ADMIN");

        assertTrue(html.contains("The chain verifies.") && html.contains("Signatures: all 2 verify"), html);
        assertTrue(html.contains(">Start of chain<") && html.contains(">email-address<") && html.contains(">9<"));
        assertTrue(html.contains("{{{REDACTED-email-address}}}") && html.contains(">claims v3<"));
        assertTrue(html.contains("action=\"/admin/ledgers/export\""), "Export");
        assertTrue(html.contains("data-confirm-dialog=\"delete-chain\""), "Delete");
    }

    @Test
    void whereVerificationFailedIsSaid() throws Exception {
        when(ledger.chain(KEY, "doc-1", null, 0, 25)).thenReturn(chain(
                "'valid':false,'hashChainValid':false,'signaturesValid':false,'signedEntries':1,'unsignedEntries':1"));
        final String broken = html("/admin/ledgers/view?document=doc-1", "ADMIN");
        assertTrue(broken.contains("The chain does not verify.") && broken.contains("Hash chain: broken."), broken);
        assertTrue(broken.contains("1 of 2 entries have none") && broken.contains("names the first such entry"));

        when(ledger.chain(KEY, "doc-2", null, 0, 25)).thenReturn(GSON.fromJson("{'documentId':'doc-2','valid':false,"
                + "'validationError':'The chain could not be validated.'}", LedgerChain.class));
        final String unreadable = html("/admin/ledgers/view?document=doc-2", "ADMIN");
        assertTrue(unreadable.contains("Philter could not check this chain."), unreadable);
        assertFalse(unreadable.contains("The chain could not be validated."), "Philter's text is not shown");
    }

    @Test
    void theEntriesArePaged() throws Exception {
        when(ledger.chain(KEY, "doc-1", "ws", 25, 25)).thenReturn(GSON.fromJson("{'documentId':'doc-1','valid':true,"
                + "'hashChainValid':true,'signaturesValid':true,'signedEntries':60,'unsignedEntries':0,'total':60,"
                + "'entries':[{'type':'person','previousHash':'x','hash':'y'}]}", LedgerChain.class));

        final String html = html("/admin/ledgers/view?document=doc-1&owner=ws&offset=25", "ADMIN");

        assertTrue(html.contains("26 to 26 of 60") || html.contains("of 60"), html);
        assertTrue(html.contains("offset=50") && html.contains("document=doc-1") && html.contains("owner=ws"),
                "the next page keeps the chain and owner");
    }

    @Test
    void exportingDownloadsPhiltersExportAndAuditsIt() throws Exception {
        final LedgerExport export = GSON.fromJson("{'version':1,'documentId':'a/b c','count':1,"
                + "'entries':[{'token':'jdoe@example.com','replacement':'r','hash':'h'}],'signingKeys':{'k':'pem'}}",
                LedgerExport.class);
        when(ledger.export(KEY, "a/b c", null)).thenReturn(export);

        final MvcResult result = mockMvc.perform(post("/admin/ledgers/export").with(signedIn("boss", "ADMIN")).with(csrf())
                .param("document", "a/b c")).andExpect(status().isOk()).andReturn();

        assertEquals("attachment; filename=\"ledger-a_b_c.json\"", result.getResponse().getHeader("Content-Disposition"));
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        final LedgerExport downloaded = GSON.fromJson(result.getResponse().getContentAsString(), LedgerExport.class);
        assertEquals("jdoe@example.com", downloaded.getEntries().get(0).getToken());
        assertEquals("pem", downloaded.getSigningKeys().get("k"));
        verify(auditLogService, never()).log(eq("LEDGER_EXPORT"), any(), any(), any());
    }

    @Test
    void anUnreadableChainIsNotExported() throws Exception {
        doThrow(new ClientException("Unprocessable", 422, "no", "entry_unreadable")).when(ledger).export(KEY, "doc-1", null);
        mockMvc.perform(post("/admin/ledgers/export").with(signedIn("boss", "ADMIN")).with(csrf()).param("document", "doc-1"))
                .andExpect(redirectedUrl("/admin/ledgers/view?document=doc-1"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.startsWith("An entry in this chain")));
        verify(auditLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void auditorsViewChainsButCannotExportOrDelete() throws Exception {
        when(ledger.chain(KEY, "doc-1", null, 0, 25)).thenReturn(chain(
                "'valid':true,'hashChainValid':true,'signaturesValid':true,'signedEntries':2,'unsignedEntries':0"));
        final String html = html("/admin/ledgers/view?document=doc-1", "AUDITOR");
        assertFalse(html.contains("/admin/ledgers/export") || html.contains("data-confirm-dialog=\""), html);

        mockMvc.perform(post("/admin/ledgers/export").with(signedIn("boss", "AUDITOR")).with(csrf()).param("document", "doc-1"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/ledgers/delete").with(signedIn("boss", "AUDITOR")).with(csrf()).param("document", "doc-1"))
                .andExpect(status().isForbidden());
        verify(ledger, never()).export(any(), any(), any());
    }

    @Test
    void deletingAChainReportsPhiltersRefusalsByTheirCause() throws Exception {
        mockMvc.perform(post("/admin/ledgers/delete").with(signedIn("boss", "ADMIN")).with(csrf()).param("document", "doc-1"))
                .andExpect(redirectedUrl("/admin/ledgers"))
                .andExpect(flash().attribute("success", "Ledger chain for document doc-1 deleted."));
        verify(ledger).delete(KEY, "doc-1", null);

        doThrow(new ClientException("Locked", 423, "held")).when(ledger).delete(KEY, "held", null);
        mockMvc.perform(post("/admin/ledgers/delete").with(signedIn("boss", "ADMIN")).with(csrf()).param("document", "held"))
                .andExpect(redirectedUrl("/admin/ledgers/view?document=held"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.startsWith("A legal hold covers this chain")));

        doThrow(new ClientException("Forbidden", 403, "off", "feature_disabled")).when(ledger).delete(KEY, "off", null);
        mockMvc.perform(post("/admin/ledgers/delete").with(signedIn("boss", "ADMIN")).with(csrf()).param("document", "off"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("LEDGER_DELETION_ENABLED=true")));

        doThrow(new ClientException("Conflict", 409, "busy")).when(ledger).delete(KEY, "busy", null);
        mockMvc.perform(post("/admin/ledgers/delete").with(signedIn("boss", "ADMIN")).with(csrf()).param("document", "busy"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.startsWith("Philter deletes only a completed chain.")));

        doThrow(new ClientException("Forbidden", 403, "admin", "admin_required")).when(ledger).delete(KEY, "notadmin", null);
        mockMvc.perform(post("/admin/ledgers/delete").with(signedIn("boss", "ADMIN")).with(csrf()).param("document", "notadmin"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("Philter administrator")));
    }

    @Test
    void reachingAnotherUserWithoutCrossUserAccessIsExplained() throws Exception {
        when(ledger.chains(eq(KEY), eq("ws"), any())).thenThrow(new ClientException("Not Found", 404, null));
        assertTrue(html("/admin/ledgers?owner=ws", "ADMIN").contains("ADMIN_CROSS_USER_ACCESS_ENABLED=true"));
    }
}
