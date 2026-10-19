/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.philter.PhilterTlsConfig;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.DataSourceHostAllowList;
import ai.philterd.arbiter.service.PhilterDefaultsService;
import ai.philterd.arbiter.service.PhilterInstanceClients;
import ai.philterd.arbiter.service.PhilterInstanceTls;
import ai.philterd.arbiter.service.SymmetricCipher;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Attaching, removing, and testing a Philter instance's own client certificate and trust store. */
class AdminPhilterControllerTlsTest {

    private static final String PASSWORD = MutualTlsFixture.PASSWORD;

    @TempDir
    static Path dir;

    private static MutualTlsFixture fixture;

    private final SymmetricCipher cipher = new SymmetricCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private PhilterInstanceRepository repository;
    private AuditLogService audit;
    private PhilterInstance instance;
    private AdminPhilterController controller;
    private HttpsServer server;

    @BeforeAll
    static void certificates() throws Exception {
        fixture = new MutualTlsFixture(dir);
    }

    @BeforeEach
    void setUp() throws Exception {
        server = fixture.start();
        instance = new PhilterInstance();
        instance.setId("p1");
        instance.setName("enclave-philter");
        instance.setEndpoint("https://localhost");
        instance.setPort(server.getAddress().getPort());
        instance.setEncryptedApiKey(cipher.encrypt("sk_test"));
        repository = mock(PhilterInstanceRepository.class);
        when(repository.findById("p1")).thenReturn(Optional.of(instance));
        audit = mock(AuditLogService.class);

        final DataSourceHostAllowList allowList = new DataSourceHostAllowList("localhost");
        final PhilterInstanceTls tls = new PhilterInstanceTls(cipher, PhilterTlsConfig.none());
        controller = new AdminPhilterController(repository, audit, mock(PhilterDefaultsService.class), cipher,
                allowList, new PhilterInstanceClients(cipher, allowList, tls), tls);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private MockMultipartFile file(final String param, final String name) throws Exception {
        return new MockMultipartFile(param, name, "application/octet-stream", fixture.bytes(name));
    }

    private static String flash(final RedirectAttributes ra, final String key) {
        final Object value = ra.getFlashAttributes().get(key);
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditDetails() {
        final ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("PHILTER_INSTANCE_TLS_CHANGE"), eq("PhilterInstance"), eq("p1"), details.capture());
        return details.getValue();
    }

    @Test
    void attachedStoresAreSavedEncryptedAndAuditedWithoutSecrets() throws Exception {
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.setTls("p1", file("keyStore", "client.p12"), PASSWORD,
                file("trustStore", "client-trust.p12"), PASSWORD, ra);

        assertNull(flash(ra, "error"));
        verify(repository).save(instance);
        assertEquals(Base64.getEncoder().encodeToString(fixture.bytes("client.p12")),
                cipher.decrypt(instance.getEncryptedKeyStore()));
        assertEquals(PASSWORD, cipher.decrypt(instance.getEncryptedKeyStorePassword()));
        assertEquals(PASSWORD, cipher.decrypt(instance.getEncryptedTrustStorePassword()));

        final Map<String, Object> details = auditDetails();
        assertEquals("added", details.get("keyStore"));
        assertEquals("added", details.get("trustStore"));
        assertFalse(details.toString().contains(PASSWORD));
        assertFalse(details.toString().contains(instance.getEncryptedKeyStore()));
    }

    @Test
    void testButtonUsesInstanceTlsSettings() throws Exception {
        RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.test("p1", ra);
        assertNotNull(flash(ra, "error"), "the stub requires a client certificate the instance doesn't have yet");

        controller.setTls("p1", file("keyStore", "client.p12"), PASSWORD,
                file("trustStore", "client-trust.p12"), PASSWORD, new RedirectAttributesModelMap());
        ra = new RedirectAttributesModelMap();
        controller.test("p1", ra);

        assertNull(flash(ra, "error"));
        assertTrue(flash(ra, "success").contains("Philter 4.0.0"), flash(ra, "success"));
    }

    @Test
    void wrongPasswordIsRejectedWithoutNamingIt() throws Exception {
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.setTls("p1", file("keyStore", "client.p12"), "wrong-pass-987", null, null, ra);

        assertTrue(flash(ra, "error").contains("client certificate keystore"), flash(ra, "error"));
        assertFalse(flash(ra, "error").contains("wrong-pass-987"));
        verify(repository, never()).save(any());
        verify(audit, never()).log(anyString(), anyString(), anyString(), anyMap());
        assertNull(instance.getEncryptedKeyStore());
    }

    @Test
    void invalidTrustStoreRejectsTheWholeSave() throws Exception {
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.setTls("p1", file("keyStore", "client.p12"), PASSWORD,
                new MockMultipartFile("trustStore", "ca.p12", "application/octet-stream", "not a keystore".getBytes()),
                PASSWORD, ra);

        assertTrue(flash(ra, "error").contains("trust store"), flash(ra, "error"));
        verify(repository, never()).save(any());
    }

    @Test
    void keystoreWithoutPasswordIsRejected() throws Exception {
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.setTls("p1", file("keyStore", "client.p12"), "", null, null, ra);

        assertTrue(flash(ra, "error").contains("needs its password"), flash(ra, "error"));
        verify(repository, never()).save(any());
    }

    @Test
    void noFilesIsRejected() {
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.setTls("p1", null, PASSWORD, new MockMultipartFile("trustStore", new byte[0]), null, ra);

        assertNotNull(flash(ra, "error"));
        verify(repository, never()).save(any());
    }

    @Test
    void oversizedFileIsRejected() {
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.setTls("p1", null, null,
                new MockMultipartFile("trustStore", "big.p12", "application/octet-stream", new byte[300 * 1024]), null, ra);

        assertTrue(flash(ra, "error").contains("larger than"), flash(ra, "error"));
        verify(repository, never()).save(any());
    }

    @Test
    void removingTheCertificateFallsBackToGlobalAndIsAudited() throws Exception {
        controller.setTls("p1", file("keyStore", "client.p12"), PASSWORD, null, null, new RedirectAttributesModelMap());
        org.mockito.Mockito.clearInvocations(audit, repository);

        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.removeTls("p1", "keyStore", ra);

        assertNull(flash(ra, "error"));
        assertNull(instance.getEncryptedKeyStore());
        assertNull(instance.getEncryptedKeyStorePassword());
        verify(repository).save(instance);
        assertEquals("removed", auditDetails().get("keyStore"));
    }

    @Test
    void removingAStoreThatIsNotSetIsRejected() {
        final RedirectAttributes ra = new RedirectAttributesModelMap();
        controller.removeTls("p1", "trustStore", ra);

        assertNotNull(flash(ra, "error"));
        verify(repository, never()).save(any());
    }
}
