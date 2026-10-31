/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.workspace.philter;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Philter calls over mutual TLS, against a local HTTPS server that requires a client certificate. */
class PhilterMutualTlsTest {

    private static final String PASSWORD = "changeit-store";
    private static final String BODY = """
            {"filteredText":"x","explanation":{"appliedSpans":[{"characterStart":0,"characterEnd":1,
             "filterType":"SSN","confidence":1.0,"text":"x"}],"ignoredSpans":[]}}
            """;

    @TempDir
    static Path dir;

    private HttpsServer server;

    @BeforeAll
    static void certificates() throws Exception {
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-keystore", path("server.p12"), "-storetype", "PKCS12", "-storepass", PASSWORD);
        keytool("-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=philter-workspace", "-keystore", path("client.p12"), "-storetype", "PKCS12", "-storepass", PASSWORD);
        keytool("-exportcert", "-alias", "server", "-keystore", path("server.p12"), "-storepass", PASSWORD,
                "-file", path("server.crt"));
        keytool("-exportcert", "-alias", "client", "-keystore", path("client.p12"), "-storepass", PASSWORD,
                "-file", path("client.crt"));
        keytool("-importcert", "-noprompt", "-alias", "server", "-file", path("server.crt"),
                "-keystore", path("client-trust.p12"), "-storetype", "PKCS12", "-storepass", PASSWORD);
        keytool("-importcert", "-noprompt", "-alias", "client", "-file", path("client.crt"),
                "-keystore", path("server-trust.p12"), "-storetype", "PKCS12", "-storepass", PASSWORD);
    }

    @BeforeEach
    void start() throws Exception {
        final SSLContext context = SSLContext.getInstance("TLS");
        final KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(KeyStore.getInstance(dir.resolve("server.p12").toFile(), PASSWORD.toCharArray()), PASSWORD.toCharArray());
        final TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(KeyStore.getInstance(dir.resolve("server-trust.p12").toFile(), PASSWORD.toCharArray()));
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(final HttpsParameters params) {
                final SSLParameters p = context.getDefaultSSLParameters();
                p.setNeedClientAuth(true);
                params.setSSLParameters(p);
            }
        });
        server.createContext("/api/explain", exchange -> {
            final byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String redactedType(final PhilterTlsConfig tls) throws IOException {
        return PhilterRedactions.redact(PhilterClients.build("https://localhost:" + server.getAddress().getPort(), "key",
                tls), "text", null, "ctx").getRedactions().get(0).getType();
    }

    @Test
    void callSucceedsWithClientCertificate() throws IOException {
        final PhilterTlsConfig tls = new PhilterTlsConfig(path("client.p12"), PASSWORD, path("client-trust.p12"), PASSWORD);
        assertEquals("ssn", redactedType(tls));
    }

    @Test
    void callIsRefusedWithoutClientCertificate() {
        final PhilterTlsConfig trustOnly = new PhilterTlsConfig("", "", path("client-trust.p12"), PASSWORD);
        assertThrows(IOException.class, () -> redactedType(trustOnly));
    }

    @Test
    void untrustedServerIsRefused() {
        final PhilterTlsConfig keyOnly = new PhilterTlsConfig(path("client.p12"), PASSWORD, "", "");
        assertThrows(IOException.class, () -> redactedType(keyOnly));
    }

    @Test
    void wrongPasswordStopsStartupWithoutRevealingIt() {
        final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PhilterTlsConfig(path("client.p12"), "wrong-password-123", "", ""));
        assertTrue(e.getMessage().contains("client.p12"));
        assertFalse(e.getMessage().contains("wrong-password-123"));
    }

    @Test
    void missingPasswordForAProtectedKeystoreStopsStartup() {
        assertThrows(IllegalStateException.class, () -> new PhilterTlsConfig(path("client.p12"), "", "", ""));
    }

    private static String path(final String name) {
        return dir.resolve(name).toString();
    }

    private static void keytool(final String... args) throws Exception {
        final List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        final Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + out);
        }
    }
}
