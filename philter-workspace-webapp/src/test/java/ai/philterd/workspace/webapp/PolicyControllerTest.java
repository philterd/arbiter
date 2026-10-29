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

import ai.philterd.workspace.repository.BatchRepository;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.PhilterSettings;
import ai.philterd.workspace.webapp.controllers.PolicyController;
import ai.philterd.philter.PhilterClient;
import ai.philterd.philter.model.ListOptions;
import ai.philterd.philter.model.Page;
import ai.philterd.philter.model.PolicySummary;
import ai.philterd.philter.model.exceptions.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyControllerTest {

    private PhilterClient client;
    private PolicyController controller;

    @BeforeEach
    void setUp() {
        client = mock(PhilterClient.class);
        final PhilterSettings philter = mock(PhilterSettings.class);
        when(philter.client()).thenReturn(client);
        controller = new PolicyController(mock(BatchRepository.class), mock(AuditLogService.class),
                mock(ai.philterd.workspace.repository.FinalizationPolicyRepository.class), philter);
    }

    /** Admin authentication used by every policy-API test below — the controller
     *  defends against non-admin callers, so a content-validation test still has
     *  to reach the validation code via an admin caller. */
    private static org.springframework.security.core.Authentication adminAuth() {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "admin@x.com", null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    // ---------- content (path-traversal validation) ----------

    @Test
    void contentRejectsPathTraversalSegment() throws Exception {
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.content("../../etc/passwd", adminAuth()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(client, never()).getPolicy(anyString());
    }

    @Test
    void contentRejectsSingleDotDotSegment() {
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.content("../status", adminAuth()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void contentRejectsSlashInName() {
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.content("valid/extra", adminAuth()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void contentRejectsNameExceedingMaxLength() {
        final String longName = "a".repeat(65);
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.content(longName, adminAuth()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void contentRejectsBlankName() {
        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.content("   ", adminAuth()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void contentReturnsPhiltersPolicyForHyphenatedAndUnderscoredNames() throws Exception {
        when(client.getPolicy("my-policy_v2")).thenReturn("{\"identifiers\":{}}");

        final java.util.Map<String, String> result = controller.content("my-policy_v2", adminAuth());

        assertEquals("my-policy_v2", result.get("name"));
        assertEquals("{\"identifiers\":{}}", result.get("content"));
    }

    @Test
    void contentOfAPolicyPhilterDoesNotHaveIsNotFound() throws Exception {
        final ClientException missing = mock(ClientException.class);
        when(missing.getStatusCode()).thenReturn(404);
        when(client.getPolicy("ghost")).thenThrow(missing);

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.content("ghost", adminAuth()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ---------- policy listing ----------

    private static PolicySummary summary(final String name) {
        final PolicySummary policy = new PolicySummary();
        policy.setName(name);
        return policy;
    }

    @Test
    void listJsonPagesThroughPhiltersPolicyNames() throws Exception {
        when(client.listPolicies(isNull(), argThat(o -> o != null && Integer.valueOf(0).equals(o.getOffset()))))
                .thenReturn(new Page<>(List.of(summary("zeta"), summary("Alpha")), 3));
        when(client.listPolicies(isNull(), argThat(o -> o != null && Integer.valueOf(2).equals(o.getOffset()))))
                .thenReturn(new Page<>(List.of(summary("beta")), 3));

        final java.util.Map<String, Object> result = controller.listJson(adminAuth());

        assertEquals(List.of("Alpha", "beta", "zeta"), result.get("policies"));
        verify(client, times(2)).listPolicies(isNull(), any(ListOptions.class));
    }

    @Test
    void listJsonReportsAnUnreachablePhilterAsBadGateway() throws Exception {
        when(client.listPolicies(isNull(), any(ListOptions.class))).thenThrow(new java.io.IOException("refused"));

        final ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.listJson(adminAuth()));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }
}
