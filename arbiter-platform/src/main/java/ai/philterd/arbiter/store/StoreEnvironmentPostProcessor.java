/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads {@code arbiter.store} and turns off Spring Boot's auto-configuration for the store not
 * in use, so a SQLite install never connects to MongoDB and a MongoDB install never looks for a
 * JDBC data source.
 */
public class StoreEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY = "arbiter.store";

    private static final List<String> MONGODB_AUTO_CONFIGURATION = List.of(
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
            "org.springframework.boot.autoconfigure.mongo.MongoReactiveAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.mongo.MongoReactiveDataAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.mongo.MongoReactiveRepositoriesAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.data.mongo.MongoHealthContributorAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.data.mongo.MongoReactiveHealthContributorAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.metrics.mongo.MongoMetricsAutoConfiguration");

    private static final List<String> JDBC_AUTO_CONFIGURATION = List.of(
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration");

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment, final SpringApplication application) {
        final String store = environment.getProperty(PROPERTY, "mongodb").trim().toLowerCase(Locale.ROOT);
        final List<String> exclude = switch (store) {
            case "mongodb" -> JDBC_AUTO_CONFIGURATION;
            case "sqlite" -> MONGODB_AUTO_CONFIGURATION;
            default -> throw new IllegalStateException(PROPERTY + " must be mongodb or sqlite, not \"" + store + "\"");
        };
        final List<String> combined = new ArrayList<>(Binder.get(environment)
                .bind("spring.autoconfigure.exclude", Bindable.listOf(String.class)).orElse(List.of()));
        for (String name : exclude) {
            // Spring Boot refuses to exclude a class that isn't on the classpath.
            if (ClassUtils.isPresent(name, getClass().getClassLoader()) && !combined.contains(name)) {
                combined.add(name);
            }
        }
        environment.getPropertySources().addFirst(new MapPropertySource("arbiterStore",
                Map.of("spring.autoconfigure.exclude", String.join(",", combined))));
    }
}
