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

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DockerRequirementTest {

    @Test
    void runsWithDocker() {
        assertDoesNotThrow(() -> DockerRequirement.check(true, "true", "the tests"));
        assertDoesNotThrow(() -> DockerRequirement.check(true, null, "the tests"));
    }

    @Test
    void isSkippedWithAMessageWithoutDockerOutsideCi() {
        final TestAbortedException skipped = assertThrows(TestAbortedException.class,
                () -> DockerRequirement.check(false, null, "the tests"));
        assertEquals("Assumption failed: Docker is not available, so this is skipped: the tests.", skipped.getMessage());
        assertThrows(TestAbortedException.class, () -> DockerRequirement.check(false, "false", "the tests"));
    }

    @Test
    void failsWithoutDockerInCi() {
        final AssertionFailedError failed = assertThrows(AssertionFailedError.class,
                () -> DockerRequirement.check(false, "true", "the tests"));
        assertEquals("Docker is required in CI: the tests.", failed.getMessage());
    }
}
