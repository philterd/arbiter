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

import ai.philterd.arbiter.model.InboxMessage;
import ai.philterd.arbiter.service.AuditLogService;
import ai.philterd.arbiter.service.InboxService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/inbox")
public class InboxController {

    private final InboxService inboxService;
    private final AuditLogService auditLogService;

    public InboxController(final InboxService inboxService,
                           final AuditLogService auditLogService) {
        this.inboxService = inboxService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public String inbox(@RequestParam(value = "showArchived", defaultValue = "false") final boolean showArchived,
                        final Authentication authentication, final Model model) {
        final String email = authentication == null ? null : authentication.getName();
        final List<InboxMessage> messages = inboxService.listForEmail(email, showArchived);
        model.addAttribute("messages", messages);
        model.addAttribute("showArchived", showArchived);
        return "inbox";
    }

    @PostMapping("/{id}/read")
    public String markRead(@PathVariable("id") final String id, final Authentication authentication) {
        final String email = authentication == null ? null : authentication.getName();
        inboxService.markRead(id, email);
        auditLogService.log("INBOX_MESSAGE_READ", "InboxMessage", id,
                Map.of("actor", email == null ? "" : email));
        return "redirect:/inbox";
    }

    @PostMapping("/{id}/archive")
    public String archive(@PathVariable("id") final String id,
                          @RequestParam(value = "showArchived", defaultValue = "false") final boolean showArchived,
                          final Authentication authentication) {
        final String email = authentication == null ? null : authentication.getName();
        inboxService.archive(id, email);
        auditLogService.log("INBOX_MESSAGE_ARCHIVED", "InboxMessage", id,
                Map.of("actor", email == null ? "" : email));
        return showArchived ? "redirect:/inbox?showArchived=true" : "redirect:/inbox";
    }
}
