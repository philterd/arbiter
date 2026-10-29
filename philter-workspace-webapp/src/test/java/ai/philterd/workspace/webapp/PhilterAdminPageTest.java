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

import ai.philterd.philter.model.AdminSettings;
import ai.philterd.philter.model.AuditLogExport;
import ai.philterd.philter.model.GetAuditLogResponse;
import ai.philterd.philter.model.SigningKey;
import ai.philterd.philter.model.UpdateAdminSettingsRequest;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.webapp.controllers.PhilterAdminController;
import ai.philterd.workspace.webapp.security.PhilterAdminService;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static ai.philterd.workspace.webapp.SignedIn.signedIn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Philter's settings and audit log, as the signed-in administrator. */
@WebMvcTest(controllers = PhilterAdminController.class)
@Import(SecurityConfig.class)
class PhilterAdminPageTest extends WebMvcSliceMocks {

    private static final String KEY = "enc-session-key";
    private static final Gson GSON = new Gson();

    @Autowired private MockMvc mockMvc;
    @MockBean private PhilterAdminService philter;
    @MockBean private AuditLogService auditLogService;

    private static AdminSettings settings() {
        final AdminSettings settings = new AdminSettings();
        settings.setMfaAvailable(true);
        settings.setSigningEnabled(true);
        settings.setWebhookAllowlist("hooks.example.com,10.0.0.0/8");
        settings.setPhieldUrl("https://phield.example.com");
        settings.setPhieldOrganization("acme");
        settings.setPhieldApiKeySet(true);
        settings.setCrossUserAccessEnabled(true);
        return settings;
    }

    @BeforeEach
    void setUp() throws Exception {
        when(philter.settings(KEY)).thenReturn(settings());
        when(philter.signingKey(KEY)).thenReturn(GSON.fromJson("{'keyId':'key-1','fingerprint':'SHA256:abc123'}",
                SigningKey.class));
        when(philter.update(eq(KEY), any())).thenReturn(settings());
    }

    private String html(final String url, final String role) throws Exception {
        return mockMvc.perform(get(url).with(signedIn("boss", role))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The settings form as it is opened, with {@code overrides} applied: name, value pairs. */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder save(final String... overrides) {
        final Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("originalMfaAvailable", "true");
        fields.put("mfaAvailable", "true");
        fields.put("originalSigningEnabled", "true");
        fields.put("signingEnabled", "true");
        fields.put("originalWebhookAllowlist", "hooks.example.com,10.0.0.0/8");
        fields.put("webhookAllowlist", "hooks.example.com,10.0.0.0/8");
        fields.put("originalPhieldUrl", "https://phield.example.com");
        fields.put("phieldUrl", "https://phield.example.com");
        fields.put("originalPhieldOrganization", "acme");
        fields.put("phieldOrganization", "acme");
        for (int i = 0; i < overrides.length; i += 2) {
            fields.put(overrides[i], overrides[i + 1]);
        }
        final org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                post("/admin/philter/settings").with(signedIn("boss", "ADMIN")).with(csrf());
        fields.forEach(request::param);
        return request;
    }

    // ---- settings

    @Test
    void theSettingsPageShowsPhiltersSettingsAndTheSigningKey() throws Exception {
        final String html = html("/admin/philter/settings", "ADMIN");

        assertTrue(html.contains("name=\"mfaAvailable\" value=\"true\" class=\"mt-0.5\" checked"), html);
        assertTrue(html.contains("hooks.example.com,10.0.0.0/8") && html.contains("value=\"https://phield.example.com\""));
        assertTrue(html.contains(">key-1<") && html.contains(">SHA256:abc123<"), "the signing key's fingerprint");
        assertTrue(html.contains("Set. Philter never shows it") && html.contains("name=\"removePhieldApiKey\""),
                "the Phield key is shown as set, never read back");
        assertTrue(html.contains("ADMIN_CROSS_USER_ACCESS_ENABLED") && html.contains(">On<"), "the environment");
        assertTrue(html.contains("aria-current=\"page\"") && html.contains(">Settings<"), "its sub-tab");
    }

    @Test
    void onlyWhatChangedIsSentAndAuditedWithoutValues() throws Exception {
        mockMvc.perform(save("mfaRequired", "true", "phieldApiKey", " secret-key "))
                .andExpect(redirectedUrl("/admin/philter/settings"))
                .andExpect(flash().attribute("success", "Philter's settings saved."));
        final ArgumentCaptor<UpdateAdminSettingsRequest> sent = ArgumentCaptor.forClass(UpdateAdminSettingsRequest.class);
        verify(philter).update(eq(KEY), sent.capture());
        assertEquals(Boolean.TRUE, sent.getValue().getMfaRequired());
        assertEquals("secret-key", sent.getValue().getPhieldApiKey());
        assertNull(sent.getValue().getMfaAvailable(), "unchanged settings are not sent");
        assertNull(sent.getValue().getWebhookAllowlist());
        assertNull(sent.getValue().getPhieldUrl());
        verify(auditLogService, never()).log(eq("PHILTER_SETTINGS_UPDATE"), any(), any(), any());
    }

    @Test
    void aBlankKeyIsKeptAndRemovingItSendsAnEmptyOne() throws Exception {
        mockMvc.perform(save("removePhieldApiKey", "true")).andExpect(redirectedUrl("/admin/philter/settings"));
        final ArgumentCaptor<UpdateAdminSettingsRequest> sent = ArgumentCaptor.forClass(UpdateAdminSettingsRequest.class);
        verify(philter).update(eq(KEY), sent.capture());
        assertEquals("", sent.getValue().getPhieldApiKey());

        mockMvc.perform(save()).andExpect(flash().attribute("success", "Nothing was changed."));
    }

    @Test
    void theAllowlistIsSentAsPhilterKeepsIt() throws Exception {
        mockMvc.perform(save("webhookAllowlist", "hooks.example.com\r\n 10.0.0.0/8 ,\r\nexample.org"))
                .andExpect(redirectedUrl("/admin/philter/settings"));
        final ArgumentCaptor<UpdateAdminSettingsRequest> sent = ArgumentCaptor.forClass(UpdateAdminSettingsRequest.class);
        verify(philter).update(eq(KEY), sent.capture());
        assertEquals("hooks.example.com,10.0.0.0/8,example.org", sent.getValue().getWebhookAllowlist());
    }

    @Test
    void settingsPhilterWouldRefuseAreNotSent() throws Exception {
        final String required = mockMvc.perform(save("mfaAvailable", "false", "mfaRequired", "true"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(required.contains("MFA can be required only while it is available."), required);

        final String noUrl = mockMvc.perform(save("phieldEnabled", "true", "phieldUrl", ""))
                .andReturn().getResponse().getContentAsString();
        assertTrue(noUrl.contains("Enter the Phield URL"), noUrl);
        assertTrue(noUrl.contains("name=\"originalPhieldUrl\" value=\"https://phield.example.com\""),
                "the form keeps what it was opened with");
        verify(philter, never()).update(any(), any());
    }

    @Test
    void philtersRefusalsAreExplained() throws Exception {
        when(philter.update(eq(KEY), any())).thenThrow(new ClientException("Bad Request", 400,
                "webhookAllowlist entry 'bad host' is not a hostname, an IP address, or a CIDR range."));
        final String html = mockMvc.perform(save("webhookAllowlist", "bad host"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Each webhook allowlist entry must be a hostname"), html);
        assertTrue(html.contains("reload it and make your change again"), "a setting changed meanwhile");
        assertFalse(html.contains("entry &#39;bad host&#39;"), "Philter's message is not shown");

        when(philter.settings(KEY)).thenThrow(new ClientException("Forbidden", 403, "no", "admin_required"));
        assertTrue(html("/admin/philter/settings", "ADMIN").contains("not a Philter administrator"));
    }

    @Test
    void philtersWarningIsShownAfterASave() throws Exception {
        final AdminSettings saved = settings();
        saved.setWarnings(List.of("The Phield URL is http, so the API key is sent in the clear. Use an https URL."));
        when(philter.update(eq(KEY), any())).thenReturn(saved);
        mockMvc.perform(save("phieldUrl", "http://phield.example.com"))
                .andExpect(flash().attribute("warnings", saved.getWarnings()));
    }

    @Test
    void theFormIsReadOnlyAndSavingRefusedForAuditors() throws Exception {
        final String html = html("/admin/philter/settings", "AUDITOR");
        assertTrue(html.contains("disabled") && !html.contains(">Save settings<"), html);
        mockMvc.perform(save().with(signedIn("boss", "AUDITOR"))).andExpect(status().isForbidden());
    }

    // ---- audit log

    @Test
    void theAuditLogIsFilteredByEventDaysAndUser() throws Exception {
        when(philter.events(eq(KEY), any(), any(), any(), any(), any())).thenReturn(GSON.fromJson("{'events':[{"
                + "'timestamp':'2026-10-20T10:00:00Z','event':'settings_updated','username':'admin',"
                + "'clientIpAddress':'10.0.0.1','source':'api','details':'changed: mfaRequired'}],'total':1}",
                GetAuditLogResponse.class));

        final String html = html("/admin/audit/philter?event=Settings_Updated&from=2026-10-20&to=2026-10-21&user=admin",
                "ADMIN");

        assertTrue(html.contains(">settings_updated<") && html.contains(">changed: mfaRequired<"), html);
        verify(philter).events(eq(KEY), eq("settings_updated"), eq(Instant.parse("2026-10-20T00:00:00Z")),
                eq(Instant.parse("2026-10-22T00:00:00Z")), eq("admin"), any());
        assertTrue(html.contains("aria-current=\"page\"") && html.contains(">Philter<"), "its sub-tab");
    }

    @Test
    void auditFilterProblemsAreExplained() throws Exception {
        assertTrue(html("/admin/audit/philter?from=yesterday", "ADMIN").contains("Enter dates as YYYY-MM-DD."));
        assertTrue(html("/admin/audit/philter?from=2026-10-22&to=2026-10-20", "ADMIN").contains("must not be after"));
        when(philter.events(eq(KEY), eq("nope"), any(), any(), any(), any()))
                .thenThrow(new ClientException("Bad Request", 400, "The event parameter is not an audit event type."));
        assertTrue(html("/admin/audit/philter?event=nope", "ADMIN").contains("The event type must be one Philter records"));
        when(philter.events(eq(KEY), any(), any(), any(), eq("jdoe"), any()))
                .thenThrow(new ClientException("Not Found", 404, null));
        assertTrue(html("/admin/audit/philter?user=jdoe", "ADMIN").contains("ADMIN_CROSS_USER_ACCESS_ENABLED=true"));
    }

    @Test
    void theExportFollowsPhiltersCursorAndKeepsOneHeader() throws Exception {
        final LocalDate from = LocalDate.parse("2026-10-01");
        final LocalDate to = LocalDate.parse("2026-10-02");
        when(philter.exportPage(KEY, from, to, null, 1000)).thenReturn(new AuditLogExport(
                "timestamp,event,details\n2026-10-02T10:00:00Z,a,\"two\nlines\"\n".getBytes(StandardCharsets.UTF_8),
                1, true, null, "c1", "UTC"));
        when(philter.exportPage(KEY, from, to, "c1", 1000)).thenReturn(new AuditLogExport(
                "timestamp,event,details\n2026-10-01T09:00:00Z,b,x\n".getBytes(StandardCharsets.UTF_8),
                1, false, null, null, "UTC"));

        final MvcResult result = mockMvc.perform(get("/admin/audit/philter/export").with(signedIn("boss", "ADMIN"))
                .param("from", "2026-10-01").param("to", "2026-10-02")).andExpect(status().isOk()).andReturn();

        assertEquals("timestamp,event,details\n2026-10-02T10:00:00Z,a,\"two\nlines\"\n2026-10-01T09:00:00Z,b,x\n",
                result.getResponse().getContentAsString());
        assertEquals("attachment; filename=\"philter-audit-2026-10-01-to-2026-10-02.csv\"",
                result.getResponse().getHeader("Content-Disposition"));
        verify(auditLogService, never()).log(eq("PHILTER_AUDIT_EXPORT"), any(), any(), any());
    }

    @Test
    void theExportStopsExactlyAtTheCap() throws Exception {
        final LocalDate day = LocalDate.parse("2026-10-01");
        final byte[] header = "timestamp,event\n".getBytes(StandardCharsets.UTF_8);
        when(philter.exportPage(eq(KEY), eq(day), eq(day), any(), anyInt())).thenAnswer(call -> {
            final int limit = call.getArgument(4);
            return new AuditLogExport(header, limit, true, null, "more", "UTC");
        });
        mockMvc.perform(get("/admin/audit/philter/export").with(signedIn("boss", "ADMIN"))
                .param("from", "2026-10-01").param("to", "2026-10-01")).andExpect(status().isOk());
        final ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(philter, org.mockito.Mockito.atLeastOnce()).exportPage(eq(KEY), eq(day), eq(day), any(), limits.capture());
        assertEquals(100_000, limits.getAllValues().stream().mapToInt(Integer::intValue).sum(), "exactly the cap");
    }

    @Test
    void anExportRangePhilterWouldRefuseIsNotRequested() throws Exception {
        mockMvc.perform(get("/admin/audit/philter/export").with(signedIn("boss", "ADMIN"))
                        .param("from", "2026-09-01").param("to", "2026-10-15"))
                .andExpect(redirectedUrl("/admin/audit/philter"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("at most 30 days")));
        verify(philter, never()).exportPage(any(), any(), any(), any(), anyInt());
    }

    @Test
    void theHeaderOfALaterPageIsDroppedEvenWithAQuotedNewline() {
        assertEquals("row\n", PhilterAdminController.afterFirstRecord(
                "\"a\nb\",c\nrow\n"));
    }
}
