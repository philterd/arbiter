/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.service;

import ai.philterd.workspace.philter.PhilterClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Logs the Philter that Philter Workspace uses, and checks once at startup whether {@code PHILTER_WORKSPACE_PHILTER_API_KEY}
 * belongs to a Philter administrator, so it is flagged in the log and under Admin → Philter. The key
 * keeps working; it is flagged, not refused. Runs off the startup thread, so an unreachable Philter
 * does not delay startup.
 */
@Component
public class PhilterKeyRoleCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PhilterKeyRoleCheck.class);

    private final PhilterSettings philter;

    public PhilterKeyRoleCheck(final PhilterSettings philter) {
        this.philter = philter;
    }

    @Override
    public void run(final ApplicationArguments args) {
        log.info("Using Philter at {} (API key set).", philter.url());
        Thread.ofVirtual().name("philter-key-role-check").start(this::check);
    }

    void check() {
        try {
            if (Boolean.TRUE.equals(philter.checkKeyRole())) {
                log.warn("PHILTER_WORKSPACE_PHILTER_API_KEY belongs to a Philter administrator. Remove that user's "
                        + "administrator role in Philter and narrow the key; see Admin > Philter.");
            }
        } catch (Exception e) {
            log.info("Could not check the role of PHILTER_WORKSPACE_PHILTER_API_KEY's user: {}", PhilterClients.describe(e));
        }
    }
}
