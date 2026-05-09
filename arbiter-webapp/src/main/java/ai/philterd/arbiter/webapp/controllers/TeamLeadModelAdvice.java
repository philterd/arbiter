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

import ai.philterd.arbiter.service.AuthUtils;
import ai.philterd.arbiter.service.UserGroupsService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes an {@code isTeamLead} model attribute to every Thymeleaf view so the sidebar
 * can show admin-tier nav entries (e.g. the Batches link) to per-group team leads.
 *
 * <p>{@code sec:authorize} expressions can only see Spring Security roles, and team
 * leadership is a per-group association rather than a role. Templates use this attribute
 * with {@code th:if="${isTeamLead}"} to conditionally render the team-lead-relevant nav.
 *
 * <p>Excluded from {@code @RestController} responses to avoid leaking the attribute into
 * JSON bodies.
 */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
public class TeamLeadModelAdvice {

    private final UserGroupsService userGroupsService;

    public TeamLeadModelAdvice(final UserGroupsService userGroupsService) {
        this.userGroupsService = userGroupsService;
    }

    @ModelAttribute("isTeamLead")
    public boolean isTeamLead(final Authentication authentication) {
        if (authentication == null || authentication.getName() == null) return false;
        return !userGroupsService.leadGroupIdsForEmail(authentication.getName()).isEmpty();
    }

    /**
     * Convenience flag for the sidebar's Batches nav link: admins, auditors, and team
     * leads all need to reach the page; regular USERs do not. Avoids duplicating the
     * three-way OR in every template.
     */
    @ModelAttribute("seesBatchesNav")
    public boolean seesBatchesNav(final Authentication authentication) {
        return AuthUtils.isAdminOrAuditor(authentication) || isTeamLead(authentication);
    }
}
