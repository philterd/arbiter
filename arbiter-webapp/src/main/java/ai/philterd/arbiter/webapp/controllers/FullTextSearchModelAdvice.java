/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp.controllers;

import ai.philterd.arbiter.service.GeneralSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the master full-text search flag as a {@code fullTextSearchEnabled} model
 * attribute on every Thymeleaf view. Templates use it with {@code th:if} to suppress
 * affordances that depend on OpenSearch — chiefly the **Search** link in the sidebar
 * and the **Find similar documents** button on the review page — so users don't see
 * controls that would lead to empty pages or non-functional behavior.
 *
 * <p>The same flag is also set explicitly by the review page controller for that
 * specific surface; this advice fills it in for every other page so individual
 * controllers don't need to repeat the lookup.
 *
 * <p>Excluded from {@code @RestController} responses (the
 * {@code annotations = Controller.class} filter) to avoid leaking the attribute into
 * JSON response bodies.
 */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
public class FullTextSearchModelAdvice {

    private static final Logger log = LoggerFactory.getLogger(FullTextSearchModelAdvice.class);

    private final GeneralSettingsService generalSettingsService;

    public FullTextSearchModelAdvice(final GeneralSettingsService generalSettingsService) {
        this.generalSettingsService = generalSettingsService;
    }

    /**
     * Reads the master flag from settings. A transient settings-load failure during
     * page rendering defaults the attribute to {@code false} so a broken navigation
     * affordance is preferred to one that would lead to broken downstream pages.
     */
    @ModelAttribute("fullTextSearchEnabled")
    public boolean fullTextSearchEnabled() {
        try {
            return generalSettingsService.load().isFullTextSearchEnabled();
        } catch (Exception e) {
            log.debug("Could not load full-text-search flag for sidebar: {}", e.getMessage());
            return false;
        }
    }
}
