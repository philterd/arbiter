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

import ai.philterd.arbiter.model.Batch;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BatchRepository extends MongoRepository<Batch, String> {
    Optional<Batch> findByName(String name);
    boolean existsByWeightSetId(String weightSetId);
    java.util.List<Batch> findByWeightSetId(String weightSetId);
    java.util.List<Batch> findByPhilterInstanceIdIsNullAndPolicyName(String policyName);
    boolean existsByFinalizationPolicyId(String finalizationPolicyId);
    java.util.List<Batch> findByFinalizationPolicyId(String finalizationPolicyId);
    java.util.List<Batch> findByComplianceProfileId(String complianceProfileId);
}
