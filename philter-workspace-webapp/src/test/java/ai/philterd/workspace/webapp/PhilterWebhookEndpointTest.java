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

import ai.philterd.workspace.api.controller.PhilterWebhookController;
import ai.philterd.workspace.webapp.security.PhilterWebhookReceiver;
import ai.philterd.workspace.webapp.security.PhilterWebhookSettings;
import ai.philterd.workspace.webapp.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Philter's webhook deliveries through the real security chain: no session, key, or CSRF token (#14). */
@WebMvcTest(controllers = PhilterWebhookController.class)
@Import(SecurityConfig.class)
class PhilterWebhookEndpointTest extends WebMvcSliceMocks {

    private static final String SECRET = "a-shared-secret-of-at-least-16-characters";
    private static final String BODY = "{\"event\":\"WEBHOOK_TEST\",\"test\":true}";

    @Autowired private MockMvc mockMvc;

    private final PhilterWebhookSettings real = new PhilterWebhookSettings(SECRET, "https://workspace.example.com/api/philter/webhook");

    @BeforeEach
    void settings() {
        when(philterWebhookSettings.enabled()).thenReturn(true);
        when(philterWebhookSettings.verify(any(), any(), any(), any())).thenAnswer(inv -> real.verify(
                inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
    }

    private static MockHttpServletRequestBuilder delivery(final String id, final String body, final String secret,
                                                          final long timestamp) throws Exception {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        final String signature = "sha256=" + HexFormat.of().formatHex(
                mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        return post(PhilterWebhookController.PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Philter-Event", "WEBHOOK_TEST")
                .header("X-Philter-Delivery-Id", id)
                .header("X-Philter-Timestamp", Long.toString(timestamp))
                .header("X-Philter-Signature", signature);
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    @Test
    void aSignedDeliveryIsAcceptedWithoutSignIn() throws Exception {
        mockMvc.perform(delivery("6a1106e9f5b4e90cb1d35a01", BODY, SECRET, now())).andExpect(status().isNoContent());
        verify(philterWebhookReceiver).receive("6a1106e9f5b4e90cb1d35a01", "WEBHOOK_TEST");
    }

    @Test
    void aRetryOfADeliveryIsAcceptedAgain() throws Exception {
        when(philterWebhookReceiver.receive("6a1106e9f5b4e90cb1d35a01", "WEBHOOK_TEST"))
                .thenReturn(PhilterWebhookReceiver.Outcome.DUPLICATE);
        mockMvc.perform(delivery("6a1106e9f5b4e90cb1d35a01", BODY, SECRET, now())).andExpect(status().isNoContent());
    }

    @Test
    void aDeliveryWithAWrongSignatureOrAnOldTimestampIsRefused() throws Exception {
        mockMvc.perform(delivery("a1", BODY, "another-secret-of-16-chars", now())).andExpect(status().isUnauthorized());
        mockMvc.perform(delivery("a2", BODY, SECRET, now() - 600)).andExpect(status().isUnauthorized());
        mockMvc.perform(delivery("a3", BODY, SECRET, now()).header("X-Philter-Signature", "sha256=00"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PhilterWebhookController.PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("X-Philter-Event", "WEBHOOK_TEST").header("X-Philter-Delivery-Id", "a4"))
                .andExpect(status().isUnauthorized());
        verify(philterWebhookReceiver, never()).receive(any(), any());
    }

    @Test
    void malformedHeadersAndLargeBodiesAreRefusedUnread() throws Exception {
        mockMvc.perform(delivery("bad id\nforged log line", BODY, SECRET, now())).andExpect(status().isBadRequest());
        mockMvc.perform(delivery("a5", BODY, SECRET, now()).header("X-Philter-Event", "test; drop"))
                .andExpect(status().isBadRequest());
        final String big = "{\"x\":\"" + "a".repeat(70 * 1024) + "\"}";
        mockMvc.perform(delivery("a6", big, SECRET, now())).andExpect(status().isPayloadTooLarge());
        verify(philterWebhookReceiver, never()).receive(any(), any());
    }

    @Test
    void withNoSecretSetThereIsNoWebhook() throws Exception {
        when(philterWebhookSettings.enabled()).thenReturn(false);
        mockMvc.perform(delivery("a7", BODY, SECRET, now())).andExpect(status().isNotFound());
        verify(philterWebhookReceiver, never()).receive(any(), any());
    }
}
