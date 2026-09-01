/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins what Actuator serves. Both exposed endpoints are unauthenticated, so the exposure list
 * is a security control: adding env, beans, or heapdump would hand configuration or memory
 * contents to any caller that can reach the port.
 */
class ActuatorExposureTest {

    /** Read from the file, not the classpath: the test classpath shadows application.properties. */
    private static Properties shippedProperties() throws IOException {
        final Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            properties.load(in);
        }
        return properties;
    }

    @Test
    void onlyHealthAndPrometheusAreExposed() throws IOException {
        assertEquals("health,prometheus",
                shippedProperties().getProperty("management.endpoints.web.exposure.include"));
    }

    /** The /actuator links page is off, so the two exposed paths are all that answer. */
    @Test
    void discoveryPageIsDisabled() throws IOException {
        assertEquals("false", shippedProperties().getProperty("management.endpoints.web.discovery.enabled"));
    }

    /** Unauthenticated callers get the aggregate status, nothing about the components. */
    @Test
    void healthDetailsAreNeverShown() throws IOException {
        final Properties properties = shippedProperties();
        assertEquals("never", properties.getProperty("management.endpoint.health.show-details"));
        assertEquals("never", properties.getProperty("management.endpoint.health.show-components"));
    }

    /** Asserting on discovered endpoints catches an upgrade that exposes something by default. */
    @Test
    void exposureListMapsToExactlyTwoWebEndpoints() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        EndpointAutoConfiguration.class,
                        WebEndpointAutoConfiguration.class,
                        HealthContributorAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class,
                        MetricsAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class,
                        PrometheusMetricsExportAutoConfiguration.class))
                .withPropertyValues("management.endpoints.web.exposure.include=health,prometheus")
                .run(context -> {
                    final Set<String> exposed = context.getBean(WebEndpointsSupplier.class).getEndpoints()
                            .stream()
                            .map(endpoint -> endpoint.getEndpointId().toString())
                            .collect(Collectors.toSet());
                    assertEquals(Set.of("health", "prometheus"), exposed);
                });
    }

}
