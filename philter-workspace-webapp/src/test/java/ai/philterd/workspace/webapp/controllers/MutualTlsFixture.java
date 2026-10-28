/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.controllers;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

/**
 * Keystores made with keytool, and a stub Philter that requires a client certificate signed into its
 * trust store. {@code client.p12} (and its JKS copy {@code client.jks}) is trusted by the server;
 * {@code stranger.p12} is not. {@code client-trust.p12} trusts the server.
 */
final class MutualTlsFixture {

    static final String PASSWORD = "changeit-store";
    static final String EXPLAIN_BODY = """
            {"filteredText":"x","explanation":{"appliedSpans":[{"characterStart":0,"characterEnd":1,
             "filterType":"SSN","confidence":1.0,"text":"x"}],"ignoredSpans":[]}}
            """;

    private final Path dir;

    MutualTlsFixture(final Path dir) throws Exception {
        this.dir = dir;
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-keystore", path("server.p12"), "-storetype", "PKCS12", "-storepass", PASSWORD);
        for (String client : List.of("client", "stranger")) {
            keytool("-genkeypair", "-alias", client, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                    "-dname", "CN=" + client, "-keystore", path(client + ".p12"), "-storetype", "PKCS12",
                    "-storepass", PASSWORD);
        }
        keytool("-importkeystore", "-srckeystore", path("client.p12"), "-srcstoretype", "PKCS12",
                "-srcstorepass", PASSWORD, "-destkeystore", path("client.jks"), "-deststoretype", "JKS",
                "-deststorepass", PASSWORD);
        keytool("-exportcert", "-alias", "server", "-keystore", path("server.p12"), "-storepass", PASSWORD,
                "-file", path("server.crt"));
        keytool("-exportcert", "-alias", "client", "-keystore", path("client.p12"), "-storepass", PASSWORD,
                "-file", path("client.crt"));
        keytool("-importcert", "-noprompt", "-alias", "server", "-file", path("server.crt"),
                "-keystore", path("client-trust.p12"), "-storetype", "PKCS12", "-storepass", PASSWORD);
        keytool("-importcert", "-noprompt", "-alias", "client", "-file", path("client.crt"),
                "-keystore", path("server-trust.p12"), "-storetype", "PKCS12", "-storepass", PASSWORD);
    }

    String path(final String name) {
        return dir.resolve(name).toString();
    }

    byte[] bytes(final String name) throws IOException {
        return Files.readAllBytes(dir.resolve(name));
    }

    /** A stub Philter serving health, limits, and explain, which refuses connections without a trusted client certificate. */
    HttpsServer start() throws Exception {
        final SSLContext context = SSLContext.getInstance("TLS");
        final KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(KeyStore.getInstance(dir.resolve("server.p12").toFile(), PASSWORD.toCharArray()), PASSWORD.toCharArray());
        final TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(KeyStore.getInstance(dir.resolve("server-trust.p12").toFile(), PASSWORD.toCharArray()));
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);

        final HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(final HttpsParameters params) {
                final SSLParameters p = context.getDefaultSSLParameters();
                p.setNeedClientAuth(true);
                params.setSSLParameters(p);
            }
        });
        server.createContext("/", exchange -> {
            final String path = exchange.getRequestURI().getPath();
            final String body = switch (path) {
                case "/api/health" -> "{\"status\":\"UP\",\"applicationVersion\":\"4.0.0\"}";
                case "/api/explain" -> EXPLAIN_BODY;
                default -> "{}";
            };
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private void keytool(final String... args) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        final Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + out);
        }
    }
}
