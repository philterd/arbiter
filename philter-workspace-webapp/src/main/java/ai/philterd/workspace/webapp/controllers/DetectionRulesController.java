/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.model.DetectionRule;
import ai.philterd.workspace.model.Document;
import ai.philterd.workspace.model.GeneralSettings;
import ai.philterd.workspace.repository.DetectionRuleRepository;
import ai.philterd.workspace.repository.DocumentRepository;
import ai.philterd.workspace.service.DetectionRuleService;
import ai.philterd.workspace.service.GeneralSettingsService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Detection Rules page (#39): rules made from reviewers' decisions, which Philter applies to every batch through
 * its never-redact and always-redact lists. Proposed rules are confirmed or dismissed here, and rules removed.
 */
@Controller
@RequestMapping("/detection-rules")
public class DetectionRulesController {

    static final String NOT_FOUND = "That rule no longer exists.";

    private final DetectionRuleRepository rules;
    private final DocumentRepository documents;
    private final DetectionRuleService service;
    private final GeneralSettingsService settings;

    public DetectionRulesController(final DetectionRuleRepository rules, final DocumentRepository documents,
                                    final DetectionRuleService service, final GeneralSettingsService settings) {
        this.rules = rules;
        this.documents = documents;
        this.service = service;
        this.settings = settings;
    }

    @GetMapping
    public String list(final Authentication auth, final Model model) {
        final GeneralSettings general = settings.load();
        final ZoneId zone = zone(general.getTimezone());
        final Map<String, Optional<Document>> documentCache = new HashMap<>();
        final List<Map<String, Object>> proposed = new ArrayList<>();
        final List<Map<String, Object>> active = new ArrayList<>();
        final List<Map<String, Object>> dismissed = new ArrayList<>();
        for (DetectionRule rule : service.visible(auth)) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rule.getId());
            row.put("kind", DetectionRule.KIND_ALWAYS.equals(rule.getKind()) ? "Always flag" : "Never flag");
            row.put("text", rule.getText() == null ? "" : rule.getText());
            row.put("piiType", rule.getPiiType() == null ? "" : rule.getPiiType());
            row.put("origin", DetectionRule.ORIGIN_REFUSALS.equals(rule.getOrigin())
                    ? "Refused " + rule.getRefusals() + " times in a batch" : "Made by a reviewer");
            row.put("proposedBy", rule.getProposedBy() == null ? "Philter Workspace" : rule.getProposedBy());
            row.put("proposedAt", at(rule.getProposedAt(), zone));
            row.put("decidedBy", rule.getDecidedBy() == null ? "" : rule.getDecidedBy());
            row.put("decidedAt", at(rule.getDecidedAt(), zone));
            final Document document = rule.getDocumentId() == null ? null
                    : documentCache.computeIfAbsent(rule.getDocumentId(), documents::findById).orElse(null);
            row.put("documentId", document == null ? null : document.getId());
            row.put("documentName", document == null ? "Removed document"
                    : document.getFilename() == null ? document.getId() : document.getFilename());
            row.put("mayConfirm", service.mayConfirm(auth, rule));
            row.put("leftInPhilter", rule.isActive() && !rule.isAddedToPhilter());
            switch (rule.getStatus() == null ? "" : rule.getStatus()) {
                case DetectionRule.STATUS_PROPOSED -> proposed.add(row);
                case DetectionRule.STATUS_ACTIVE -> active.add(row);
                default -> dismissed.add(row);
            }
        }
        model.addAttribute("proposed", proposed);
        model.addAttribute("active", active);
        model.addAttribute("dismissed", dismissed);
        model.addAttribute("ruleProposalRefusals", general.getRuleProposalRefusals());
        model.addAttribute("reviewersConfirmRules", general.isReviewersConfirmRules());
        return "detection-rules";
    }

    @PostMapping("/{id}/confirm")
    public String confirm(@PathVariable final String id, final Authentication auth, final RedirectAttributes ra) {
        return act(id, auth, ra, rule -> {
            service.confirm(rule, auth);
            return "Rule confirmed. Philter applies it to documents redacted from now on.";
        });
    }

    @PostMapping("/{id}/dismiss")
    public String dismiss(@PathVariable final String id, final Authentication auth, final RedirectAttributes ra) {
        return act(id, auth, ra, rule -> {
            service.dismiss(rule, auth);
            return "Proposal dismissed. The value will not be proposed again unless the dismissed rule is removed.";
        });
    }

    @PostMapping("/{id}/remove")
    public String remove(@PathVariable final String id, final Authentication auth, final RedirectAttributes ra) {
        return act(id, auth, ra, rule -> {
            final boolean wasActive = rule.isActive();
            if (service.remove(rule, auth)) {
                return "Rule removed, and its value taken off Philter's list for documents redacted from now on.";
            }
            return wasActive ? "Rule removed. Its value stays on Philter's list, since the rule did not put it there."
                    : "Rule removed.";
        });
    }

    private interface Action {
        String apply(DetectionRule rule);
    }

    private String act(final String id, final Authentication auth, final RedirectAttributes ra, final Action action) {
        // A rule the person cannot see is reported the same as one that does not exist.
        final DetectionRule rule = rules.findById(id)
                .filter(r -> service.canSee(auth, r))
                .orElse(null);
        if (rule == null) {
            ra.addFlashAttribute("error", NOT_FOUND);
            return "redirect:/detection-rules";
        }
        try {
            ra.addFlashAttribute("success", action.apply(rule));
        } catch (DetectionRuleService.RuleException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/detection-rules";
    }

    private static ZoneId zone(final String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException e) {
            return ZoneId.of("UTC");
        }
    }

    private static ZonedDateTime at(final Instant instant, final ZoneId zone) {
        return instant == null ? null : instant.atZone(zone);
    }
}
