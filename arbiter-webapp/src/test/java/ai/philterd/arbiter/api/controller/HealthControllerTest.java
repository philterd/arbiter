/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    private static HealthEndpoint endpointReporting(final HealthComponent health) {
        final HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(health);
        return endpoint;
    }

    private static HealthController controller(final HealthComponent health, final String buildVersion) {
        final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (buildVersion != null) {
            final Properties properties = new Properties();
            properties.setProperty("version", buildVersion);
            beans.registerSingleton("buildProperties", new BuildProperties(properties));
        }
        return new HealthController(endpointReporting(health), beans.getBeanProvider(BuildProperties.class));
    }

    @Test
    void reportsTheBuildVersion() {
        final ResponseEntity<Map<String, String>> response = controller(Health.up().build(), "1.2.3").health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("1.2.3", response.getBody().get("applicationVersion"));
    }

    /** Without build-info.properties there is no BuildProperties bean, and health still answers. */
    @Test
    void reportsUnknownWithoutBuildInfo() {
        final ResponseEntity<Map<String, String>> response = controller(Health.up().build(), null).health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("unknown", response.getBody().get("applicationVersion"));
    }

    /** A monitor reading only the status code must not see a broken dependency as healthy. */
    @Test
    void reportsServiceUnavailableWhenTheAggregateIsDown() {
        final ResponseEntity<Map<String, String>> response = controller(Health.down().build(), "1.2.3").health();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("DOWN", response.getBody().get("status"));
        assertEquals("1.2.3", response.getBody().get("applicationVersion"));
    }

    /** The body carries the status and the version, and nothing that names a component. */
    @Test
    void bodyCarriesOnlyStatusAndVersion() {
        final ResponseEntity<Map<String, String>> response = controller(
                Health.down().withDetail("mongo", "connection refused to db.internal:27017").build(),
                "1.2.3").health();

        assertEquals(Map.of("status", "DOWN", "applicationVersion", "1.2.3"), response.getBody());
    }

}
