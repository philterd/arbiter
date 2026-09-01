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

import ai.philterd.arbiter.model.GeneralSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Cluster probe behind {@code /actuator/health}, against a loopback server, not a mock. */
class OpenSearchIndexServiceProbeTest {

    private static OpenSearchIndexService serviceFor(final boolean enabled, final String endpoint) {
        final GeneralSettings settings = new GeneralSettings();
        settings.setFullTextSearchEnabled(enabled);
        settings.setOpensearchEndpoint(endpoint);
        final GeneralSettingsService generalSettingsService = mock(GeneralSettingsService.class);
        when(generalSettingsService.load()).thenReturn(settings);
        return new OpenSearchIndexService(generalSettingsService);
    }

    /** Serves the given status on every path, on a port the OS picks. */
    private static HttpServer serverReturning(final int statusCode) throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String urlOf(final HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void answeringClusterIsReachable() throws IOException {
        final HttpServer server = serverReturning(200);
        try {
            assertEquals(OpenSearchIndexService.ClusterState.REACHABLE,
                    serviceFor(true, urlOf(server)).probeCluster());
        } finally {
            server.stop(0);
        }
    }

    /** A cluster that answers but refuses the request (usually a bad credential) is not usable. */
    @Test
    void clusterRejectingTheRequestIsUnreachable() throws IOException {
        final HttpServer server = serverReturning(401);
        try {
            assertEquals(OpenSearchIndexService.ClusterState.UNREACHABLE,
                    serviceFor(true, urlOf(server)).probeCluster());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void clusterThatIsNotListeningIsUnreachable() throws IOException {
        // Bind a port, note it, release it: an address nothing answers on.
        final HttpServer server = serverReturning(200);
        final String url = urlOf(server);
        server.stop(0);

        assertEquals(OpenSearchIndexService.ClusterState.UNREACHABLE, serviceFor(true, url).probeCluster());
    }

    /** Feature off means no network call, even though settings always carry a default endpoint. */
    @Test
    void featureOffReportsDisabledWithoutProbing() {
        assertEquals(OpenSearchIndexService.ClusterState.DISABLED,
                serviceFor(false, "http://127.0.0.1:9200").probeCluster());
    }

    @Test
    void blankEndpointReportsDisabled() {
        assertEquals(OpenSearchIndexService.ClusterState.DISABLED, serviceFor(true, "  ").probeCluster());
    }

}
