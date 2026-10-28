/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.store.mongo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/** The MongoDB store, selected with {@code philter-workspace.store=mongodb} (the default). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "philter-workspace", name = "store", havingValue = "mongodb", matchIfMissing = true)
@EnableMongoRepositories(basePackages = "ai.philterd.workspace.repository")
public class MongoStoreConfiguration {
}
