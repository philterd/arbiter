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

import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry;
import org.springframework.boot.health.registry.DefaultReactiveHealthContributorRegistry;

import java.util.List;
import java.util.Map;

/**
 * A real health report for tests. Spring Boot 4's {@link HealthDescriptor} is sealed, so it can be neither mocked
 * nor subclassed; this builds one from a {@link HealthEndpoint} over a single indicator reporting {@code status}.
 */
public final class TestHealth {

    private TestHealth() {
    }

    public static HealthDescriptor reporting(final Status status) {
        final HealthIndicator indicator = () -> Health.status(status).build();
        final DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry(List.of(),
                register -> register.accept("test", indicator));
        final HealthEndpointGroup group = new HealthEndpointGroup() {
            @Override public boolean isMember(final String name) { return true; }
            @Override public boolean showComponents(final SecurityContext context) { return false; }
            @Override public boolean showDetails(final SecurityContext context) { return false; }
            @Override public StatusAggregator getStatusAggregator() { return StatusAggregator.getDefault(); }
            @Override public HttpCodeStatusMapper getHttpCodeStatusMapper() { return HttpCodeStatusMapper.DEFAULT; }
            @Override public AdditionalHealthEndpointPath getAdditionalPath() { return null; }
        };
        return new HealthEndpoint(registry, new DefaultReactiveHealthContributorRegistry(),
                HealthEndpointGroups.of(group, Map.of()), null).health();
    }
}
