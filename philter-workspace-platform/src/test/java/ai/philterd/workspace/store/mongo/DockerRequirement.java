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

import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Skips a test class that needs Docker when Docker is unavailable, except in CI, where it fails instead. */
final class DockerRequirement {

    private DockerRequirement() {
    }

    static void require(final String what) {
        check(DockerClientFactory.instance().isDockerAvailable(), System.getenv("CI"), what);
    }

    static void check(final boolean dockerAvailable, final String ci, final String what) {
        if (dockerAvailable) {
            return;
        }
        if ("true".equalsIgnoreCase(ci)) {
            fail("Docker is required in CI: " + what + ".");
        }
        assumeTrue(false, "Docker is not available, so this is skipped: " + what + ".");
    }
}
