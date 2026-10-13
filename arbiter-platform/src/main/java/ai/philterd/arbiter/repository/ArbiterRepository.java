/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.repository;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.ListPagingAndSortingRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Store-neutral base for Arbiter's repositories. Each configured store (see
 * {@code arbiter.store}) provides the implementation, so repositories declare only
 * derived queries that every store supports.
 */
@NoRepositoryBean
public interface ArbiterRepository<T, ID> extends ListCrudRepository<T, ID>, ListPagingAndSortingRepository<T, ID> {
}
