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

import ai.philterd.philter.model.ContextDetails;
import ai.philterd.philter.model.ContextSummary;
import ai.philterd.philter.model.DisambiguationScope;
import ai.philterd.philter.model.exceptions.ErrorReasons;
import ai.philterd.philter.model.exceptions.PhilterError;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.webapp.security.PhilterContextsService;
import ai.philterd.workspace.webapp.security.PhilterSessionAuthentication;
import ai.philterd.workspace.webapp.security.PhilterSessions;
import ai.philterd.workspace.webapp.ui.Confirmation;
import ai.philterd.workspace.webapp.ui.FormErrors;
import ai.philterd.workspace.webapp.ui.ListView;
import ai.philterd.workspace.webapp.ui.OwnerFilter;
import ai.philterd.workspace.webapp.ui.PhilterNames;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Philter's contexts, which hold the replacements consistent pseudonymization reuses and what span
 * disambiguation learns, managed with the signed-in person's session key, so Philter's per-user ownership
 * applies. An administrator can choose another user's contexts, or every user's, with the owner filter, which
 * Philter allows only with {@code ADMIN_CROSS_USER_ACCESS_ENABLED=true}. Batches are redacted with Philter
 * Workspace's own key, so a batch's context is that key's user's. Philter UI's Contexts page is the reference.
 */
@Controller
public class PhilterContextsController {

    private static final Logger log = LoggerFactory.getLogger(PhilterContextsController.class);

    static final String PATH = "/policies/contexts";

    public static final String CROSS_USER = "Philter found no such user you can reach. Reaching another user's "
            + "contexts needs ADMIN_CROSS_USER_ACCESS_ENABLED=true in Philter.";

    // Philter's messages are not shown (see FormErrors); every refusal of a context change carries a reason.
    private static final Map<String, String> REASONS = Map.of(
            ErrorReasons.CONTEXT_EXISTS, "A context with that name already exists.",
            ErrorReasons.CONTEXT_LIMIT_REACHED, "A Philter user can have at most 10 contexts, counting default. "
                    + "Delete one first.",
            ErrorReasons.CONTEXT_IN_USE, "Redaction jobs that use this context are pending or running, so it "
                    + "cannot be deleted yet. Try again when they finish.");
    private static final Map<String, String> FIELDS = Map.of("name", "Choose another name.");

    /** Philter's disambiguation scopes, with how the form names them. */
    static final Map<String, String> SCOPES = scopes();

    private final PhilterContextsService contexts;

    public PhilterContextsController(final PhilterContextsService contexts) {
        this.contexts = contexts;
    }

    /**
     * One context in the list: whose it is when not the person's own, its details (null when Philter could not
     * give them), and the confirmations for its destructive actions.
     */
    public record Row(ContextSummary context, String owner, ContextDetails details, Confirmation clearing,
                      Confirmation deletion) {
        Row(final ContextSummary context, final String owner, final ContextDetails details) {
            this(context, owner, details, PhilterContextsController.clearing(context.getName(), owner, details),
                    PhilterContextsController.deletion(context.getName(), owner));
        }

        public String viewUrl() { return PATH + "/view" + query(context.getName(), owner); }
        public String editUrl() { return PATH + "/edit" + query(context.getName(), owner); }
        public String settings() { return PhilterContextsController.settings(details); }
    }

    /** The form state the new and edit pages render, kept when a save is refused. */
    public record ContextForm(String name, String owner, boolean disambiguation, String scope, boolean ledger,
                              boolean existing) {
    }

    // ---- list and view

    @GetMapping(PATH)
    public String list(final Authentication authentication, final HttpServletRequest request, final Model model) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        ListView list = ListView.of(request, PATH, Set.of("name", "created"), "name", List.of())
                .withOwner(OwnerFilter.from(authentication, request.getParameter("owner"), request.getParameter("all")));
        final OwnerFilter owner = list.owner();
        final String key = person.getEncryptedSessionKey();
        final List<Row> rows = new ArrayList<>();
        try {
            final List<ContextSummary> page;
            if (owner.everyone()) {
                final var all = contexts.listAcrossUsers(key, list.options());
                page = all.getContexts() == null ? List.of() : all.getContexts();
                list = list.withTotal(all.getTotal());
            } else {
                final var mine = contexts.list(key, owner.username(), list.options());
                page = mine.getContexts() == null ? List.of() : mine.getContexts();
                list = list.withTotal(mine.getTotal());
            }
            // Philter's listing has no sizes, so each context's details are read: at most 10, a user's most. Every
            // user's contexts are listed without them, as in Philter UI, since a page could need 100 reads, each one
            // recorded in Philter's audit log as cross-user access.
            for (final ContextSummary context : page) {
                rows.add(owner.everyone() ? new Row(context, context.getOwner(), null)
                        : new Row(context, owner.username(), detailsOrNull(key, context.getName(), owner.username())));
            }
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            model.addAttribute("error", listError(e, !owner.mine()));
        }
        model.addAttribute("activeTab", "contexts");
        model.addAttribute("list", list);
        model.addAttribute("rows", rows);
        model.addAttribute("canWrite", AuthUtils.isAdmin(authentication));
        model.addAttribute("newUrl", PATH + "/new" + (owner.username() == null ? "" : "?owner=" + encode(owner.username())));
        return "contexts";
    }

    /** A context's settings and its entries counted by filter type. */
    @GetMapping(PATH + "/view")
    public String view(@RequestParam("name") final String name,
                       @RequestParam(value = "owner", required = false) final String requestedOwner,
                       final Authentication authentication, final HttpServletRequest request, final Model model,
                       final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        final ContextDetails details;
        try {
            details = contexts.details(person.getEncryptedSessionKey(), name, owner);
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", actionError(e, owner));
            return "redirect:" + listUrl(owner);
        }
        model.addAttribute("name", name);
        model.addAttribute("owner", owner);
        model.addAttribute("details", details);
        model.addAttribute("settings", settings(details));
        model.addAttribute("counts", details.getFilterTypes() == null ? Map.of() : details.getFilterTypes());
        model.addAttribute("backUrl", listUrl(owner));
        model.addAttribute("canWrite", AuthUtils.isAdmin(authentication));
        model.addAttribute("editUrl", PATH + "/edit" + query(name, owner));
        model.addAttribute("clearing", clearing(name, owner, details));
        model.addAttribute("deletion", deletion(name, owner));
        return "context-view";
    }

    // ---- create and edit

    @GetMapping(PATH + "/new")
    public String newContext(@RequestParam(value = "owner", required = false) final String requestedOwner,
                             final Authentication authentication, final Model model) {
        // Only administrators change contexts (SecurityConfig refuses anyone else's POST), so no one else gets the form.
        if (!AuthUtils.isAdmin(authentication)) {
            return "redirect:" + PATH;
        }
        return form(model, new ContextForm("", ownerFor(authentication, requestedOwner), false,
                DisambiguationScope.DOCUMENT, false, false), null);
    }

    @PostMapping(PATH + "/new")
    public String create(@RequestParam("name") final String name,
                         @RequestParam(value = "disambiguation", defaultValue = "false") final boolean disambiguation,
                         @RequestParam(value = "scope", defaultValue = DisambiguationScope.DOCUMENT) final String scope,
                         @RequestParam(value = "ledger", defaultValue = "false") final boolean ledger,
                         @RequestParam(value = "owner", required = false) final String requestedOwner,
                         final Authentication authentication, final HttpServletRequest request, final Model model,
                         final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        final String trimmed = name == null ? "" : name.trim();
        final String chosenScope = SCOPES.containsKey(scope) ? scope : DisambiguationScope.DOCUMENT;
        final ContextForm form = new ContextForm(trimmed, owner, disambiguation, chosenScope, ledger, false);
        final String nameProblem = PhilterNames.problem(trimmed, "context");
        if (nameProblem != null) {
            return form(model, form, new FormErrors(nameProblem, Map.of("name", nameProblem)));
        }
        try {
            contexts.create(person.getEncryptedSessionKey(), trimmed, disambiguation, chosenScope, ledger, owner);
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            return form(model, form, saveErrors(e, owner));
        }
        flash.addFlashAttribute("success", "Context \"" + trimmed + "\" created.");
        return "redirect:" + listUrl(owner);
    }

    @GetMapping(PATH + "/edit")
    public String edit(@RequestParam("name") final String name,
                       @RequestParam(value = "owner", required = false) final String requestedOwner,
                       final Authentication authentication, final HttpServletRequest request, final Model model,
                       final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        if (!AuthUtils.isAdmin(authentication)) {
            return "redirect:" + PATH + "/view" + query(name, owner);
        }
        final ContextDetails details;
        try {
            details = contexts.details(person.getEncryptedSessionKey(), name, owner);
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", actionError(e, owner));
            return "redirect:" + listUrl(owner);
        }
        return form(model, new ContextForm(name, owner, details.isEntityTypeDisambiguation(),
                scopeOf(details.getDisambiguationScope()), details.isLedger(), true), null);
    }

    /** Sends only the settings that changed from the ones the form was opened with. */
    @PostMapping(PATH + "/edit")
    public String save(@RequestParam("name") final String name,
                       @RequestParam(value = "disambiguation", defaultValue = "false") final boolean disambiguation,
                       @RequestParam(value = "scope", defaultValue = DisambiguationScope.DOCUMENT) final String scope,
                       @RequestParam(value = "ledger", defaultValue = "false") final boolean ledger,
                       @RequestParam("originalDisambiguation") final boolean originalDisambiguation,
                       @RequestParam("originalScope") final String originalScope,
                       @RequestParam("originalLedger") final boolean originalLedger,
                       @RequestParam(value = "owner", required = false) final String requestedOwner,
                       final Authentication authentication, final HttpServletRequest request, final Model model,
                       final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        final String chosenScope = SCOPES.containsKey(scope) ? scope : DisambiguationScope.DOCUMENT;
        final Boolean newDisambiguation = disambiguation == originalDisambiguation ? null : disambiguation;
        final String newScope = chosenScope.equals(originalScope) ? null : chosenScope;
        final Boolean newLedger = ledger == originalLedger ? null : ledger;
        if (newDisambiguation == null && newScope == null && newLedger == null) {
            flash.addFlashAttribute("success", "Context \"" + name + "\" was not changed.");
            return "redirect:" + listUrl(owner);
        }
        try {
            contexts.update(person.getEncryptedSessionKey(), name, newDisambiguation, newScope, newLedger, owner);
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            // The form keeps the settings it was opened with, so a second save still sends only what changed.
            model.addAttribute("originalDisambiguation", originalDisambiguation);
            model.addAttribute("originalScope", originalScope);
            model.addAttribute("originalLedger", originalLedger);
            return form(model, new ContextForm(name, owner, disambiguation, chosenScope, ledger, true),
                    saveErrors(e, owner));
        }
        flash.addFlashAttribute("success", "Context \"" + name + "\" saved.");
        return "redirect:" + listUrl(owner);
    }

    // ---- clear and delete

    @GetMapping(PATH + "/clear")
    public String confirmClear(@RequestParam("name") final String name,
                               @RequestParam(value = "owner", required = false) final String requestedOwner,
                               final Authentication authentication, final Model model) {
        model.addAttribute("confirmation", clearing(name, ownerFor(authentication, requestedOwner), null));
        return "confirm";
    }

    @PostMapping(PATH + "/clear")
    public String clear(@RequestParam("name") final String name,
                        @RequestParam(value = "owner", required = false) final String requestedOwner,
                        final Authentication authentication, final HttpServletRequest request,
                        final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        try {
            contexts.clear(person.getEncryptedSessionKey(), name, owner);
            flash.addFlashAttribute("success", "Context \"" + name + "\" cleared.");
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", actionError(e, owner));
        }
        return "redirect:" + listUrl(owner);
    }

    @GetMapping(PATH + "/delete")
    public String confirmDelete(@RequestParam("name") final String name,
                                @RequestParam(value = "owner", required = false) final String requestedOwner,
                                final Authentication authentication, final Model model) {
        model.addAttribute("confirmation", deletion(name, ownerFor(authentication, requestedOwner)));
        return "confirm";
    }

    @PostMapping(PATH + "/delete")
    public String delete(@RequestParam("name") final String name,
                         @RequestParam(value = "owner", required = false) final String requestedOwner,
                         final Authentication authentication, final HttpServletRequest request,
                         final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        try {
            contexts.delete(person.getEncryptedSessionKey(), name, owner);
            flash.addFlashAttribute("success", "Context \"" + name + "\" deleted.");
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", actionError(e, owner));
        }
        return "redirect:" + listUrl(owner);
    }

    static Confirmation clearing(final String name, final String owner, final ContextDetails details) {
        final String entries = details == null ? "Its entries are" : details.getSize() == 1 ? "Its 1 entry is"
                : "Its " + details.getSize() + " entries are";
        return new Confirmation("Clear the context " + name + "?",
                entries + " deleted, with what span disambiguation learned for it, so later redactions in it no "
                        + "longer reuse earlier replacements. The context and its settings are kept. This cannot be "
                        + "undone.",
                List.of(), PATH + "/clear", "Clear", PATH + "/clear" + query(name, owner), listUrl(owner),
                fields(name, owner));
    }

    static Confirmation deletion(final String name, final String owner) {
        return new Confirmation("Delete the context " + name + "?",
                "It is deleted with its entries and what span disambiguation learned for it, and Philter refuses "
                        + "redactions that name it, such as a batch's, until it is created again. This cannot be undone.",
                List.of(), PATH + "/delete", "Delete", PATH + "/delete" + query(name, owner), listUrl(owner),
                fields(name, owner));
    }

    private static Map<String, String> fields(final String name, final String owner) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", name);
        if (owner != null) {
            fields.put("owner", owner);
        }
        return fields;
    }

    // ---- helpers

    /** A context's settings in a few words, or an empty string when they are not known. */
    static String settings(final ContextDetails details) {
        if (details == null) {
            return "";
        }
        final String disambiguation = details.isEntityTypeDisambiguation()
                ? "Disambiguation within " + (DisambiguationScope.CONTEXT.equals(scopeOf(details.getDisambiguationScope()))
                        ? "the context" : "each document")
                : "No disambiguation";
        return disambiguation + (details.isLedger() ? "; ledger on" : "");
    }

    private static String scopeOf(final String scope) {
        return scope != null && SCOPES.containsKey(scope) ? scope : DisambiguationScope.DOCUMENT;
    }

    private static Map<String, String> scopes() {
        final Map<String, String> scopes = new LinkedHashMap<>();
        scopes.put(DisambiguationScope.DOCUMENT, "Each document");
        scopes.put(DisambiguationScope.CONTEXT, "The whole context, kept between documents");
        return scopes;
    }

    private ContextDetails detailsOrNull(final String key, final String name, final String owner) {
        try {
            return contexts.details(key, name, owner);
        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not read a Philter context's details: {}", PhilterClients.describe(e));
            return null;
        }
    }

    private String form(final Model model, final ContextForm form, final FormErrors errors) {
        model.addAttribute("form", form);
        model.addAttribute("errors", errors);
        model.addAttribute("scopes", SCOPES);
        model.addAttribute("backUrl", listUrl(form.owner()));
        if (!model.containsAttribute("originalScope")) {
            model.addAttribute("originalDisambiguation", form.disambiguation());
            model.addAttribute("originalScope", form.scope());
            model.addAttribute("originalLedger", form.ledger());
        }
        return "context-form";
    }

    private static FormErrors saveErrors(final Exception e, final String owner) {
        if (owner != null && e instanceof PhilterError error && error.getStatusCode() == 404) {
            return FormErrors.of(CROSS_USER);
        }
        return FormErrors.from(e, REASONS, FIELDS);
    }

    private static String actionError(final Exception e, final String owner) {
        if (owner != null && e instanceof PhilterError error && error.getStatusCode() == 404) {
            return CROSS_USER;
        }
        return FormErrors.from(e, REASONS, Map.of()).message();
    }

    private static String listError(final Exception e, final boolean otherUser) {
        if (otherUser && e instanceof PhilterError error && error.getStatusCode() == 404) {
            return CROSS_USER;
        }
        if (e instanceof PhilterError error && error.getStatusCode() == 403) {
            return "Philter refused: your Philter account cannot read contexts.";
        }
        log.warn("Could not list Philter contexts: {}", PhilterClients.describe(e));
        return "Philter could not be reached, so contexts cannot be listed.";
    }

    /** The owner an administrator chose, or null: anyone else's choice is ignored, as on the list. */
    private static String ownerFor(final Authentication authentication, final String owner) {
        return OwnerFilter.from(authentication, owner, null).username();
    }

    private static PhilterSessionAuthentication person(final Authentication authentication) {
        return authentication instanceof PhilterSessionAuthentication philterSession ? philterSession : null;
    }

    static String listUrl(final String owner) {
        return owner == null ? PATH : PATH + "?owner=" + encode(owner);
    }

    private static String query(final String name, final String owner) {
        return "?name=" + encode(name) + (owner == null ? "" : "&owner=" + encode(owner));
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
