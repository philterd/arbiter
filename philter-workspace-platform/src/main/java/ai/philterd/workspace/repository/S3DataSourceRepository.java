/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.repository;

import ai.philterd.workspace.model.S3DataSource;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface S3DataSourceRepository extends WorkspaceRepository<S3DataSource, String> {
    Optional<S3DataSource> findByName(String name);
    Optional<S3DataSource> findFirstByNameIgnoreCase(String name);
}
