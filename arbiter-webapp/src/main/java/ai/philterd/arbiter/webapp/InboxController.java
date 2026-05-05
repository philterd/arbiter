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

import ai.philterd.arbiter.model.InboxMessage;
import ai.philterd.arbiter.service.InboxService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("/inbox")
public class InboxController {

    private final InboxService inboxService;

    public InboxController(final InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @GetMapping
    public String inbox(final Authentication authentication, final Model model) {
        final String email = authentication == null ? null : authentication.getName();
        final List<InboxMessage> messages = inboxService.listForEmail(email);
        model.addAttribute("messages", messages);
        return "inbox";
    }

    @PostMapping("/{id}/read")
    public String markRead(@PathVariable("id") final String id, final Authentication authentication) {
        inboxService.markRead(id, authentication == null ? null : authentication.getName());
        return "redirect:/inbox";
    }
}
