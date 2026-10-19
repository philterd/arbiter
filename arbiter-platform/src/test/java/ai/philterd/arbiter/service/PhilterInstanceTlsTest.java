/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.service;

import ai.philterd.arbiter.core.model.RedactionResponse;
import ai.philterd.arbiter.model.PhilterInstance;
import ai.philterd.arbiter.philter.DefaultPhilterClientFactory;
import ai.philterd.arbiter.philter.PhilterClient;
import ai.philterd.arbiter.philter.PhilterTlsConfig;
import ai.philterd.arbiter.repository.PhilterInstanceRepository;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Per-instance client certificates and trust stores, against a stub Philter that requires mutual TLS. */
class PhilterInstanceTlsTest {

    private static final String PASSWORD = MutualTlsFixture.PASSWORD;

    @TempDir
    static Path dir;

    private static MutualTlsFixture fixture;

    private final SymmetricCipher cipher = new SymmetricCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final DataSourceHostAllowList allowList = new DataSourceHostAllowList("localhost");
    private HttpsServer server;
    private PhilterInstance instance;

    @BeforeAll
    static void certificates() throws Exception {
        fixture = new MutualTlsFixture(dir);
    }

    @BeforeEach
    void start() throws Exception {
        server = fixture.start();
        instance = new PhilterInstance();
        instance.setId("p1");
        instance.setName("enclave-philter");
        instance.setEndpoint("https://localhost");
        instance.setPort(server.getAddress().getPort());
        instance.setEncryptedApiKey(cipher.encrypt("sk_test"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private PhilterInstanceTls tls(final PhilterTlsConfig global) {
        return new PhilterInstanceTls(cipher, global);
    }

    private ai.philterd.philter.PhilterClient client(final PhilterInstanceTls tls) throws IOException {
        return new PhilterInstanceClients(cipher, allowList, tls).client(instance);
    }

    @Test
    void instanceCertificateAndTrustStoreCompleteMutualTls() throws Exception {
        final PhilterInstanceTls tls = tls(PhilterTlsConfig.none());
        tls.setKeyStore(instance, fixture.bytes("client.p12"), PASSWORD);
        tls.setTrustStore(instance, fixture.bytes("client-trust.p12"), PASSWORD);

        assertEquals("4.0.0", client(tls).health().getApplicationVersion());
    }

    @Test
    void jksKeystoreWorks() throws Exception {
        final PhilterInstanceTls tls = tls(PhilterTlsConfig.none());
        tls.setKeyStore(instance, fixture.bytes("client.jks"), PASSWORD);
        tls.setTrustStore(instance, fixture.bytes("client-trust.p12"), PASSWORD);

        assertEquals("4.0.0", client(tls).health().getApplicationVersion());
    }

    @Test
    void instanceWithoutSettingsUsesGlobalSettings() throws Exception {
        final PhilterTlsConfig global = new PhilterTlsConfig(fixture.path("client.p12"), PASSWORD,
                fixture.path("client-trust.p12"), PASSWORD);

        assertEquals("4.0.0", client(tls(global)).health().getApplicationVersion());
        assertThrows(IOException.class, () -> client(tls(PhilterTlsConfig.none())).health());
    }

    @Test
    void ownTrustStoreFallsBackToGlobalCertificate() throws Exception {
        final PhilterTlsConfig globalKeyOnly = new PhilterTlsConfig(fixture.path("client.p12"), PASSWORD, "", "");
        final PhilterInstanceTls tls = tls(globalKeyOnly);
        assertThrows(IOException.class, () -> client(tls).health(), "the JVM's trust store doesn't trust the stub");

        tls.setTrustStore(instance, fixture.bytes("client-trust.p12"), PASSWORD);
        assertEquals("4.0.0", client(tls).health().getApplicationVersion());
    }

    @Test
    void ownCertificateReplacesGlobalCertificate() throws Exception {
        final PhilterTlsConfig globalStranger = new PhilterTlsConfig(fixture.path("stranger.p12"), PASSWORD,
                fixture.path("client-trust.p12"), PASSWORD);
        final PhilterInstanceTls tls = tls(globalStranger);
        assertThrows(IOException.class, () -> client(tls).health(), "the stub doesn't trust the global certificate");

        tls.setKeyStore(instance, fixture.bytes("client.p12"), PASSWORD);
        assertEquals("4.0.0", client(tls).health().getApplicationVersion());
    }

    @Test
    void redactionUsesInstanceTlsSettings() throws Exception {
        final PhilterInstanceTls tls = tls(PhilterTlsConfig.none());
        tls.setKeyStore(instance, fixture.bytes("client.p12"), PASSWORD);
        tls.setTrustStore(instance, fixture.bytes("client-trust.p12"), PASSWORD);
        final PhilterInstanceRepository repository = mock(PhilterInstanceRepository.class);
        when(repository.findById("p1")).thenReturn(Optional.of(instance));

        final RedactionServiceImpl service = new RedactionServiceImpl(mock(PhilterClient.class),
                new DefaultPhilterClientFactory(PhilterTlsConfig.none()), repository, cipher, allowList, tls);

        final RedactionResponse response = service.redactText("x", "p1", "default", "ctx");
        assertEquals(1, response.getRedactions().size());
    }

    @Test
    void storesAndPasswordsAreEncryptedAtRest() throws Exception {
        final byte[] keyStore = fixture.bytes("client.p12");
        tls(PhilterTlsConfig.none()).setKeyStore(instance, keyStore, PASSWORD);

        final String base64 = Base64.getEncoder().encodeToString(keyStore);
        assertFalse(instance.getEncryptedKeyStore().contains(base64.substring(0, 40)));
        assertFalse(instance.getEncryptedKeyStorePassword().contains(PASSWORD));
        assertEquals(base64, cipher.decrypt(instance.getEncryptedKeyStore()));
        assertEquals(PASSWORD, cipher.decrypt(instance.getEncryptedKeyStorePassword()));
    }

    @Test
    void wrongPasswordIsRejectedWithoutRevealingIt() throws Exception {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tls(PhilterTlsConfig.none()).setKeyStore(instance, fixture.bytes("client.p12"), "wrong-pass-987"));

        assertTrue(e.getMessage().contains("client certificate keystore"), e.getMessage());
        assertFalse(e.getMessage().contains("wrong-pass-987"));
        assertNull(instance.getEncryptedKeyStore());
    }

    @Test
    void storeWithoutPrivateKeyIsRejectedAsKeystore() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tls(PhilterTlsConfig.none()).setKeyStore(instance, fixture.bytes("client-trust.p12"), PASSWORD));
        assertTrue(e.getMessage().contains("no private key"), e.getMessage());
    }

    @Test
    void protectedTrustStoreWithoutItsPasswordAsksForIt() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tls(PhilterTlsConfig.none()).setTrustStore(instance, fixture.bytes("client-trust.p12"), ""));
        assertTrue(e.getMessage().contains("without a password"), e.getMessage());
    }

    @Test
    void garbageIsRejected() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tls(PhilterTlsConfig.none()).setTrustStore(instance, "not a keystore".getBytes(), PASSWORD));
        assertTrue(e.getMessage().contains("PKCS12 or JKS"), e.getMessage());
    }

    @Test
    void contextIsReusedUntilAStoreChanges() throws Exception {
        final PhilterInstanceTls tls = tls(PhilterTlsConfig.none());
        tls.setKeyStore(instance, fixture.bytes("client.p12"), PASSWORD);
        final SSLContext first = tls.sslContext(instance);
        assertSame(first, tls.sslContext(instance));

        tls.setKeyStore(instance, fixture.bytes("client.p12"), PASSWORD);
        assertNotSame(first, tls.sslContext(instance));

        tls.clearKeyStore(instance);
        assertNull(tls.sslContext(instance), "no instance or global settings leaves the SDK default");
    }

    @Test
    void storesThatNoLongerDecryptFailTheCallInsteadOfFallingBack() throws Exception {
        tls(PhilterTlsConfig.none()).setKeyStore(instance, fixture.bytes("client.p12"), PASSWORD);
        final SymmetricCipher rotated = new SymmetricCipher(Base64.getEncoder().encodeToString(new byte[]{
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28,
                29, 30, 31, 32}));

        final IOException e = assertThrows(IOException.class,
                () -> new PhilterInstanceTls(rotated, PhilterTlsConfig.none()).sslContext(instance));
        assertTrue(e.getMessage().contains("enclave-philter"), e.getMessage());
    }
}
