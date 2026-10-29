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

import ai.philterd.philter.model.ApiKey;
import ai.philterd.philter.model.ApiKeyScopeDescription;
import ai.philterd.philter.model.CurrentUser;
import ai.philterd.philter.model.GetApiKeysResponse;
import ai.philterd.philter.model.GetWebhookDeliveriesResponse;
import ai.philterd.philter.model.MfaEnrollment;
import ai.philterd.philter.model.Webhook;
import ai.philterd.philter.model.WebhookDelivery;
import ai.philterd.philter.model.WebhookTestResult;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import ai.philterd.workspace.service.UserSettingsService;
import ai.philterd.workspace.webapp.controllers.PhilterAccountController;
import ai.philterd.workspace.webapp.controllers.SettingsController;
import ai.philterd.workspace.webapp.security.MfaQrCodes;
import ai.philterd.workspace.webapp.security.PhilterAccountService;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import com.google.gson.Gson;
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

/** The person's Philter account on the Settings tabs: account details, MFA, API keys, and webhook. */
@WebMvcTest(controllers = {PhilterAccountController.class, SettingsController.class})
@Import({SecurityConfig.class, MfaQrCodes.class})
class PhilterAccountPageTest extends WebMvcSliceMocks {

    private static final String KEY = "enc-session-key";
    private static final Gson GSON = new Gson();

    @Autowired private MockMvc mockMvc;
    @MockBean private PhilterAccountService account;
    @MockBean private UserSettingsService userSettingsService;
    @MockBean private ai.philterd.workspace.service.AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        when(userSettingsService.loadForEmail(any())).thenReturn(new ai.philterd.workspace.model.UserSettings());
    }

    private static CurrentUser user(final boolean mfaAvailable, final boolean mfaEnabled, final boolean mfaRequired) {
        return GSON.fromJson("{\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"role\":\"user\","
                + "\"mfaAvailable\":" + mfaAvailable + ",\"mfaEnabled\":" + mfaEnabled + ",\"mfaRequired\":"
                + mfaRequired + "}", CurrentUser.class);
    }

    private static ApiKey key(final String id, final String prefix, final boolean bootstrap, final String... scopes) {
        return GSON.fromJson("{\"id\":\"" + id + "\",\"prefix\":\"" + prefix + "\",\"bootstrap\":" + bootstrap
                + ",\"created\":\"2026-10-01T10:00:00Z\",\"scopes\":" + GSON.toJson(scopes) + "}", ApiKey.class);
    }

    private static GetApiKeysResponse keys(final ApiKey... keys) {
        return GSON.fromJson("{\"apiKeys\":" + GSON.toJson(keys) + ",\"total\":" + keys.length + "}",
                GetApiKeysResponse.class);
    }

    private String html(final String url) throws Exception {
        return mockMvc.perform(get(url).with(signedIn("jdoe", "USER"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ---- account and MFA

    @Test
    void settingsShowsTheAccountFromPhilterAndOffersMfaSetUp() throws Exception {
        when(philterSignInService.currentUser(KEY)).thenReturn(user(true, false, false));

        final String html = html("/settings");

        assertTrue(html.contains(">jdoe<") && html.contains(">jdoe@example.com<") && html.contains(">user<"), html);
        assertTrue(html.contains("href=\"/settings/mfa\""), "Set up MFA");
        assertTrue(html.contains("href=\"/settings/api-keys\"") && html.contains("href=\"/settings/webhook\""), "tabs");
    }

    @Test
    void mfaIsNotOfferedWhenPhilterHasItOff() throws Exception {
        when(philterSignInService.currentUser(KEY)).thenReturn(user(false, false, false));
        assertFalse(html("/settings").contains("Multi-factor authentication"));
    }

    @Test
    void anEnrolledPersonCanRemoveMfaWithACode() throws Exception {
        when(philterSignInService.currentUser(KEY)).thenReturn(user(true, true, true));
        final String html = html("/settings");
        assertTrue(html.contains("action=\"/settings/mfa/remove\"") && html.contains("Philter requires it"), html);

        mockMvc.perform(post("/settings/mfa/remove").with(signedIn("jdoe", "USER")).with(csrf()).param("code", " 123456 "))
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("success", "Multi-factor authentication removed."));
        verify(account).removeMfa(KEY, "123456");

        doThrow(new ClientException("Forbidden", 403, "The code is not valid.", "invalid_code"))
                .when(account).removeMfa(KEY, "000000");
        mockMvc.perform(post("/settings/mfa/remove").with(signedIn("jdoe", "USER")).with(csrf()).param("code", "000000"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.startsWith("The code is not valid.")));
    }

    @Test
    void settingUpMfaShowsTheQrCodeThenEndsTheSession() throws Exception {
        final MfaEnrollment enrollment = GSON.fromJson("{\"secret\":\"JBSWY3DPEHPK3PXP\",\"otpauthUri\":"
                + "\"otpauth://totp/Philter:jdoe?secret=JBSWY3DPEHPK3PXP&issuer=Philter\"}", MfaEnrollment.class);
        when(philterSignInService.startMfaEnrollment(KEY, "current-password")).thenReturn(enrollment);

        final String qr = mockMvc.perform(post("/settings/mfa/start").with(signedIn("jdoe", "USER")).with(csrf())
                        .param("currentPassword", "current-password"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(qr.contains("src=\"data:image/png;base64,") && qr.contains("JBSWY3DPEHPK3PXP"), qr);

        mockMvc.perform(post("/settings/mfa/confirm").with(signedIn("jdoe", "USER")).with(csrf()).param("code", "123456"))
                .andExpect(redirectedUrl("/login?mfaEnrolled"));
        verify(philterSignInService).confirmMfaEnrollment(KEY, "123456");
    }

    @Test
    void aWrongPasswordForMfaIsExplained() throws Exception {
        when(philterSignInService.startMfaEnrollment(KEY, "wrong")).thenThrow(
                new ClientException("Forbidden", 403, "The password is not correct.", "wrong_password"));
        final String html = mockMvc.perform(post("/settings/mfa/start").with(signedIn("jdoe", "USER")).with(csrf())
                        .param("currentPassword", "wrong"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("The password is not correct."), html);
        assertFalse(html.contains("data:image/png"), "no QR code");
    }

    // ---- API keys

    @Test
    void theKeysListFlagsPhilterWorkspacesKeyAndTheBootstrapKey() throws Exception {
        when(philterSettings.apiKey()).thenReturn("sk_workspacekey0123456789");
        when(account.apiKeys(eq(KEY), any())).thenReturn(keys(
                key("k1", "sk_workspace...", false, "redact", "policies:read"),
                key("k2", "sk_bootstrap...", true, "redact")));

        final String html = html("/settings/api-keys");

        assertTrue(html.contains("sk_workspace...") && html.contains("Philter Workspace's key<"), html);
        assertTrue(html.contains("Bootstrap key") && html.contains("redact, policies:read"));
        assertTrue(html.contains("ingest and batch redaction stop until it is replaced"), "the revoke warning");
        assertTrue(html.contains("which Philter does not recreate"), "the bootstrap warning");
        assertTrue(html.contains("href=\"/settings/api-keys/scopes?id=k1\""));
    }

    @Test
    void scopesCanOnlyBeNarrowed() throws Exception {
        when(account.apiKeys(eq(KEY), any())).thenReturn(keys(key("k1", "sk_abcdefghi...", false, "redact", "lists:read")));
        final ApiKeyScopeDescription redact = GSON.fromJson("{\"name\":\"redact\",\"description\":\"Redact text\"}",
                ApiKeyScopeDescription.class);
        final ApiKeyScopeDescription admin = GSON.fromJson("{\"name\":\"users:write\",\"description\":\"Manage users\"}",
                ApiKeyScopeDescription.class);
        when(account.scopes(KEY)).thenReturn(List.of(redact, admin));

        final String form = html("/settings/api-keys/scopes?id=k1");
        assertTrue(form.contains("value=\"redact\"") && form.contains("value=\"lists:read\""), form);
        assertFalse(form.contains("value=\"users:write\""), "a scope the key does not hold is not offered");
        assertTrue(form.contains("Redact text"), "described");

        when(account.setScopes(KEY, "k1", List.of("redact"))).thenReturn(key("k1", "sk_abcdefghi...", false, "redact"));
        mockMvc.perform(post("/settings/api-keys/scopes").with(signedIn("jdoe", "USER")).with(csrf())
                        .param("id", "k1").param("scopes", "redact"))
                .andExpect(redirectedUrl("/settings/api-keys"))
                .andExpect(flash().attribute("success", "Scopes of sk_abcdefghi... changed."));

        final String none = mockMvc.perform(post("/settings/api-keys/scopes").with(signedIn("jdoe", "USER")).with(csrf())
                        .param("id", "k1"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(none.contains("A key keeps at least one scope."), none);
        verify(account, never()).setScopes(any(), any(), eq(List.of()));
    }

    @Test
    void aKeyPastTheFirstHundredIsFound() throws Exception {
        final ApiKey[] first = new ApiKey[100];
        for (int i = 0; i < first.length; i++) {
            first[i] = key("other" + i, "sk_other" + i + "...", false, "redact");
        }
        final GetApiKeysResponse page1 = GSON.fromJson("{\"apiKeys\":" + GSON.toJson(first) + ",\"total\":101}",
                GetApiKeysResponse.class);
        final GetApiKeysResponse page2 = GSON.fromJson("{\"apiKeys\":" + GSON.toJson(new ApiKey[]{
                key("last", "sk_lastkey12...", false, "redact")}) + ",\"total\":101}", GetApiKeysResponse.class);
        when(account.apiKeys(eq(KEY), org.mockito.ArgumentMatchers.argThat(o -> o != null && o.getOffset() == 0)))
                .thenReturn(page1);
        when(account.apiKeys(eq(KEY), org.mockito.ArgumentMatchers.argThat(o -> o != null && o.getOffset() == 100)))
                .thenReturn(page2);

        assertTrue(html("/settings/api-keys/revoke?id=last").contains("Revoke the key sk_lastkey12...?"));
    }

    @Test
    void revokingAKeyGoesToPhilter() throws Exception {
        mockMvc.perform(post("/settings/api-keys/revoke").with(signedIn("jdoe", "USER")).with(csrf()).param("id", "k1"))
                .andExpect(redirectedUrl("/settings/api-keys"))
                .andExpect(flash().attribute("success", "Key revoked."));
        verify(account).revoke(KEY, "k1");
        verify(auditLogService, never()).log(eq("API_KEY_REVOKE"), any(), any(), any());
    }

    @Test
    void auditorsManageTheirOwnAccount() throws Exception {
        mockMvc.perform(post("/settings/api-keys/revoke").with(signedIn("jdoe", "AUDITOR")).with(csrf()).param("id", "k1"))
                .andExpect(redirectedUrl("/settings/api-keys"));
        verify(account).revoke(KEY, "k1");
    }

    @Test
    void anEndedPhilterSessionSignsThePersonOut() throws Exception {
        when(account.apiKeys(eq(KEY), any())).thenThrow(new UnauthorizedException("Unauthorized"));
        mockMvc.perform(get("/settings/api-keys").with(signedIn("jdoe", "USER")))
                .andExpect(redirectedUrl("/login?expired"));
    }

    // ---- webhook

    @Test
    void theWebhookPageShowsTheUrlAndDeliveriesWithTheirErrors() throws Exception {
        when(account.webhook(KEY)).thenReturn(GSON.fromJson("{\"url\":\"https://hooks.example.com/philter\","
                + "\"secretSet\":true}", Webhook.class));
        final WebhookDelivery failed = GSON.fromJson("{\"id\":\"d1\",\"documentId\":\"doc-1\",\"event\":\"FAILED\","
                + "\"status\":\"FAILED\",\"attempts\":5,\"lastError\":\"HTTP 503\"}", WebhookDelivery.class);
        when(account.deliveries(eq(KEY), any())).thenReturn(GSON.fromJson("{\"deliveries\":" + GSON.toJson(List.of(failed))
                + ",\"total\":1}", GetWebhookDeliveriesResponse.class));

        final String html = html("/settings/webhook");

        assertTrue(html.contains("value=\"https://hooks.example.com/philter\"") && html.contains("with a secret"), html);
        assertTrue(html.contains(">doc-1<") && html.contains(">HTTP 503<") && html.contains(">5<"), "the delivery");
        assertTrue(html.contains("Leave blank to keep the current secret."));
        assertTrue(html.contains("data-generate-secret=\"secret\""), "Generate");
        assertTrue(html.contains("data-confirm-dialog=\"remove-webhook\""), "Remove is confirmed");
    }

    @Test
    void savingTheWebhookChecksTheUrlAndSecretFirst() throws Exception {
        mockMvc.perform(post("/settings/webhook").with(signedIn("jdoe", "USER")).with(csrf())
                        .param("url", "ftp://hooks.example.com").param("secret", "s".repeat(16)))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.startsWith("Enter an http or https URL")));
        mockMvc.perform(post("/settings/webhook").with(signedIn("jdoe", "USER")).with(csrf())
                        .param("url", "https://hooks.example.com/philter").param("secret", "short"))
                .andExpect(flash().attribute("error", "The secret must be at least 16 characters."));
        mockMvc.perform(post("/settings/webhook").with(signedIn("jdoe", "USER")).with(csrf())
                        .param("url", "https://hooks.example.com/philter"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.startsWith("Enter a secret")));
        verify(account, never()).setWebhook(any(), any(), any());

        mockMvc.perform(post("/settings/webhook").with(signedIn("jdoe", "USER")).with(csrf())
                        .param("url", " https://hooks.example.com/new ").param("secretSet", "true"))
                .andExpect(redirectedUrl("/settings/webhook"))
                .andExpect(flash().attribute("success", "Webhook saved."));
        verify(account).setWebhook(KEY, "https://hooks.example.com/new", null);
        verify(auditLogService, never()).log(eq("WEBHOOK_SET"), any(), any(), any());
    }

    @Test
    void aUrlPhilterRefusesIsExplainedAsTheAllowlist() throws Exception {
        doThrow(new ClientException("Bad Request", 400, "The webhook host is not permitted."))
                .when(account).setWebhook(anyString(), anyString(), anyString());
        mockMvc.perform(post("/settings/webhook").with(signedIn("jdoe", "USER")).with(csrf())
                        .param("url", "https://10.0.0.1/hook").param("secret", "s".repeat(16)))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("allowlist")))
                .andExpect(flash().attribute("url", "https://10.0.0.1/hook"));
    }

    @Test
    void theTestEventSaysWhatTheReceiverAnswered() throws Exception {
        when(account.testWebhook(KEY)).thenReturn(GSON.fromJson("{\"delivered\":false,\"statusCode\":500,"
                + "\"durationMillis\":12}", WebhookTestResult.class));
        mockMvc.perform(post("/settings/webhook/test").with(signedIn("jdoe", "USER")).with(csrf()))
                .andExpect(flash().attribute("error", "The test event was not delivered: the receiver answered HTTP 500."));

        mockMvc.perform(post("/settings/webhook/remove").with(signedIn("jdoe", "USER")).with(csrf()))
                .andExpect(flash().attribute("success", "Webhook removed."));
        verify(account).removeWebhook(KEY);
    }
}
