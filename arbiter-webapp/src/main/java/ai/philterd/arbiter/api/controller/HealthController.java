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
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code GET /api/health} - liveness probe, matching the contract Philter and Philter
 * Router expose. Sits outside {@code /api/v1} because it is not a versioned business
 * endpoint, and is the one API path reachable without authentication so a container
 * runtime or load balancer can probe it.
 */
@RestController
public class HealthController {

    private final String version;

    /**
     * The version comes from META-INF/build-info.properties, written by the build-info goal
     * of the Spring Boot Maven plugin. Running from classes built outside Maven (an IDE, for
     * example) has no such file and therefore no BuildProperties bean, so the version reports
     * as unknown rather than failing startup.
     */
    public HealthController(final ObjectProvider<BuildProperties> buildProperties) {
        final BuildProperties build = buildProperties.getIfAvailable();
        this.version = build != null ? build.getVersion() : "unknown";
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "applicationVersion", version);
    }

}
