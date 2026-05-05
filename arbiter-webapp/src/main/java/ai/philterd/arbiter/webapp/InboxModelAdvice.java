/*
 * Copyright 2026 Philterd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.arbiter.webapp;

import ai.philterd.arbiter.service.InboxService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Adds the current user's unread inbox count to every Thymeleaf-rendered view so the sidebar
 * badge can render without each controller having to remember to populate it.
 */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
public class InboxModelAdvice {

    private final InboxService inboxService;

    public InboxModelAdvice(final InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @ModelAttribute("inboxUnreadCount")
    public long inboxUnreadCount(final Authentication authentication) {
        if (authentication == null) return 0L;
        try {
            return inboxService.unreadCountForEmail(authentication.getName());
        } catch (Exception e) {
            return 0L;
        }
    }
}
