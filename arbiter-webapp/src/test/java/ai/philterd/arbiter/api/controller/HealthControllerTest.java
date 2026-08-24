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
import org.springframework.boot.info.BuildProperties;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HealthControllerTest {

    @Test
    void reportsTheBuildVersion() {
        final Properties properties = new Properties();
        properties.setProperty("version", "1.2.3");

        final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("buildProperties", new BuildProperties(properties));

        final HealthController controller = new HealthController(beans.getBeanProvider(BuildProperties.class));

        assertEquals("UP", controller.health().get("status"));
        assertEquals("1.2.3", controller.health().get("applicationVersion"));
    }

    /** Without build-info.properties there is no BuildProperties bean, and health still answers. */
    @Test
    void reportsUnknownWithoutBuildInfo() {
        final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();

        final HealthController controller = new HealthController(beans.getBeanProvider(BuildProperties.class));

        assertEquals("UP", controller.health().get("status"));
        assertEquals("unknown", controller.health().get("applicationVersion"));
    }

}
