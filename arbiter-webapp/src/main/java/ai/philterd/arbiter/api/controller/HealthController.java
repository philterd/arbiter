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

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code GET /api/health} - the health endpoint every Philterd product exposes:
 * {@code {"status": ..., "applicationVersion": ...}}, 200 when {@code UP} and 503 when not.
 * Unauthenticated, and outside {@code /api/v1} because it is not versioned business API.
 *
 * <p>The status is Actuator's aggregate, so a broken dependency shows here instead of being
 * masked by the process still serving pages. Adds the build version {@code /actuator/health}
 * does not carry.
 */
@RestController
public class HealthController {

    private final HealthEndpoint healthEndpoint;

    private final String version;

    /**
     * The version comes from META-INF/build-info.properties, written by the Spring Boot Maven
     * plugin. Classes built outside Maven (an IDE) have no such file, so the version reports
     * as unknown rather than failing startup.
     */
    public HealthController(final HealthEndpoint healthEndpoint,
                            final ObjectProvider<BuildProperties> buildProperties) {
        this.healthEndpoint = healthEndpoint;
        final BuildProperties build = buildProperties.getIfAvailable();
        this.version = build != null ? build.getVersion() : "unknown";
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, String>> health() {
        final Status status = healthEndpoint.health().getStatus();
        final boolean up = Status.UP.getCode().equals(status.getCode());
        // Status only: which dependency is down is for the logs, not an anonymous caller.
        return ResponseEntity
                .status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", status.getCode(), "applicationVersion", version));
    }

}
