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

import ai.philterd.philter.model.LegalHoldRequest;
import ai.philterd.philter.model.LegalHoldResponse;
import ai.philterd.philter.model.OwnedLegalHoldResponse;
import ai.philterd.philter.model.Page;
import ai.philterd.philter.model.exceptions.ErrorReasons;
import ai.philterd.philter.model.exceptions.PhilterError;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.webapp.security.PhilterHoldsService;
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
 * Philter's legal holds, which block the deletion of Philter's ledger evidence until released, managed with the
 * signed-in person's session key under Admin. An administrator can choose another user's holds, or every user's,
 * with the owner filter, which Philter allows only with {@code ADMIN_CROSS_USER_ACCESS_ENABLED=true}; batches are
 * redacted with Philter Workspace's own key, so their evidence is that key's user's. Not the "keep original
 * documents" finalization option, which is Philter Workspace's own. Philter UI's Legal Holds page is the reference.
 */
@Controller
public class PhilterHoldsController {

    private static final Logger log = LoggerFactory.getLogger(PhilterHoldsController.class);

    static final String PATH = "/admin/holds";
    static final String SCOPE_DOCUMENT = "document_chain";
    static final String SCOPE_USER = "user";

    public static final String CROSS_USER = "Philter found no such user you can reach. Reaching another user's "
            + "holds needs ADMIN_CROSS_USER_ACCESS_ENABLED=true in Philter.";

    static final String GONE = "Philter has no hold with that reference. It may have been released already.";
    static final String GONE_OR_CROSS_USER = "Philter has no hold with that reference for that user, or no such user "
            + "you can reach. It may have been released already; reaching another user's holds needs "
            + "ADMIN_CROSS_USER_ACCESS_ENABLED=true in Philter.";

    // Philter's messages are not shown (see FormErrors); each refusal carries a reason.
    private static final Map<String, String> REASONS = Map.of(
            ErrorReasons.HOLD_EXISTS, "A hold with that reference already exists.",
            ErrorReasons.OPERATION_IN_PROGRESS, "Philter is running a deletion or hold change for this user's "
                    + "evidence, or one needs recovery. Try again shortly.",
            ErrorReasons.ADMIN_REQUIRED, "Only the person who placed this hold, or a Philter administrator, can "
                    + "release it.");

    private final PhilterHoldsService holds;
    private final AuditLogService auditLogService;

    public PhilterHoldsController(final PhilterHoldsService holds, final AuditLogService auditLogService) {
        this.holds = holds;
        this.auditLogService = auditLogService;
    }

    /** One hold in the list, with whose evidence it covers when not the person's own. */
    public record Row(LegalHoldResponse hold, String owner, Confirmation release) {
        public String scopeLabel() { return PhilterHoldsController.scopeLabel(hold.getScopeType()); }
    }

    /** The form state the new hold page renders, kept when Philter refuses it. */
    public record HoldForm(String reference, String scope, String documentId, String reason, String owner) {
    }

    @GetMapping(PATH)
    public String list(final Authentication authentication, final HttpServletRequest request, final Model model) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        // No default sort, so Philter's own order applies: the newest hold first.
        ListView list = ListView.of(request, PATH, Set.of("set", "reference"), null, List.of())
                .withOwner(OwnerFilter.from(authentication, request.getParameter("owner"), request.getParameter("all")));
        final OwnerFilter owner = list.owner();
        final List<Row> rows = new ArrayList<>();
        try {
            if (owner.everyone()) {
                final Page<OwnedLegalHoldResponse> page = holds.listAcrossUsers(person.getEncryptedSessionKey(),
                        list.options());
                page.getItems().forEach(h -> rows.add(row(h, h.getOwner())));
                list = list.withTotal(page.getTotal());
            } else {
                final Page<LegalHoldResponse> page = holds.list(person.getEncryptedSessionKey(), owner.username(),
                        list.options());
                page.getItems().forEach(h -> rows.add(row(h, owner.username())));
                list = list.withTotal(page.getTotal());
            }
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            model.addAttribute("error", listError(e, !owner.mine()));
        }
        model.addAttribute("list", list);
        model.addAttribute("rows", rows);
        model.addAttribute("canWrite", AuthUtils.isAdmin(authentication));
        model.addAttribute("newUrl", PATH + "/new" + (owner.username() == null ? "" : "?owner=" + encode(owner.username())));
        return "admin-holds";
    }

    @GetMapping(PATH + "/new")
    public String newHold(@RequestParam(value = "owner", required = false) final String requestedOwner,
                          final Authentication authentication, final Model model) {
        // Only administrators place holds (SecurityConfig refuses anyone else's POST), so no one else gets the form.
        if (!AuthUtils.isAdmin(authentication)) {
            return "redirect:" + PATH;
        }
        return form(model, new HoldForm("", SCOPE_DOCUMENT, "", "", ownerFor(authentication, requestedOwner)), null);
    }

    @PostMapping(PATH + "/new")
    public String place(@RequestParam("reference") final String reference,
                        @RequestParam(value = "scope", defaultValue = SCOPE_DOCUMENT) final String scope,
                        @RequestParam(value = "documentId", defaultValue = "") final String documentId,
                        @RequestParam(value = "reason", defaultValue = "") final String reason,
                        @RequestParam(value = "owner", required = false) final String requestedOwner,
                        final Authentication authentication, final HttpServletRequest request, final Model model,
                        final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        final String trimmed = reference == null ? "" : reference.trim();
        final String chosenScope = SCOPE_USER.equals(scope) ? SCOPE_USER : SCOPE_DOCUMENT;
        final String document = documentId.trim();
        final HoldForm form = new HoldForm(trimmed, chosenScope, document, reason, owner);
        final String referenceProblem = PhilterNames.problem(trimmed, "hold reference");
        if (referenceProblem != null) {
            return form(model, form, new FormErrors(referenceProblem, Map.of("reference", referenceProblem)));
        }
        if (SCOPE_DOCUMENT.equals(chosenScope) && document.isEmpty()) {
            final String message = "Enter the ID of the document whose ledger chain the hold covers.";
            return form(model, form, new FormErrors(message, Map.of("documentId", message)));
        }
        final LegalHoldRequest hold = new LegalHoldRequest();
        hold.setReference(trimmed);
        hold.setScopeType(chosenScope);
        // A user hold covers its owner, which Philter fills in.
        hold.setScopeValue(SCOPE_DOCUMENT.equals(chosenScope) ? document : null);
        hold.setReason(reason.isBlank() ? null : reason.trim());
        try {
            holds.place(person.getEncryptedSessionKey(), hold, owner);
            audit("LEGAL_HOLD_SET", owner, Map.of("scopeType", chosenScope));
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            final FormErrors errors = owner != null && e instanceof PhilterError error && error.getStatusCode() == 404
                    ? FormErrors.of(CROSS_USER) : FormErrors.from(e, REASONS, Map.of());
            return form(model, form, errors);
        }
        flash.addFlashAttribute("success", "Hold \"" + trimmed + "\" placed.");
        return "redirect:" + listUrl(owner);
    }

    @GetMapping(PATH + "/release")
    public String confirmRelease(@RequestParam("reference") final String reference,
                                 @RequestParam(value = "owner", required = false) final String requestedOwner,
                                 final Authentication authentication, final Model model) {
        model.addAttribute("confirmation", release(reference, ownerFor(authentication, requestedOwner)));
        return "confirm";
    }

    @PostMapping(PATH + "/release")
    public String doRelease(@RequestParam("reference") final String reference,
                            @RequestParam(value = "owner", required = false) final String requestedOwner,
                            final Authentication authentication, final HttpServletRequest request,
                            final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        try {
            holds.release(person.getEncryptedSessionKey(), reference, owner);
            audit("LEGAL_HOLD_RELEASE", owner, Map.of());
            flash.addFlashAttribute("success", "Hold \"" + reference + "\" released.");
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            // For another user's hold, a 404 is either a hold that is gone or an owner Philter will not reach.
            flash.addFlashAttribute("error", e instanceof PhilterError error && error.getStatusCode() == 404
                    ? (owner == null ? GONE : GONE_OR_CROSS_USER) : FormErrors.from(e, REASONS, Map.of()).message());
        }
        return "redirect:" + listUrl(owner);
    }

    static Confirmation release(final String reference, final String owner) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("reference", reference);
        if (owner != null) {
            fields.put("owner", owner);
        }
        return new Confirmation("Release the hold " + reference + "?",
                "The evidence it covers can be deleted or purged again, unless another hold still covers it. "
                        + "This is recorded in Philter's audit log.",
                List.of(), PATH + "/release", "Release", PATH + "/release" + query(reference, owner), listUrl(owner),
                fields);
    }

    // ---- helpers

    /**
     * Records a hold change in Philter Workspace's audit log, naming whose evidence it is. The reference is left
     * out, since it is free text that can name a person; Philter's audit log keeps it.
     */
    private void audit(final String action, final String owner, final Map<String, Object> details) {
        final Map<String, Object> all = new LinkedHashMap<>(details);
        all.put("method", "philter");
        all.put("owner", owner == null ? "self" : owner);
        auditLogService.log(action, "LegalHold", null, all);
    }

    private static Row row(final LegalHoldResponse hold, final String owner) {
        return new Row(hold, owner, release(hold.getReference(), owner));
    }

    static String scopeLabel(final String scopeType) {
        return SCOPE_USER.equals(scopeType) ? "All of the user's evidence" : "One document's ledger chain";
    }

    private String form(final Model model, final HoldForm form, final FormErrors errors) {
        model.addAttribute("form", form);
        model.addAttribute("errors", errors);
        model.addAttribute("backUrl", listUrl(form.owner()));
        return "admin-hold-form";
    }

    private static String listError(final Exception e, final boolean otherUser) {
        if (otherUser && e instanceof PhilterError error && error.getStatusCode() == 404) {
            return CROSS_USER;
        }
        if (e instanceof PhilterError error && error.getStatusCode() == 403) {
            return "Philter refused: your Philter account cannot read legal holds.";
        }
        log.warn("Could not list Philter legal holds: {}", PhilterClients.describe(e));
        return "Philter could not be reached, so legal holds cannot be listed.";
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

    private static String query(final String reference, final String owner) {
        return "?reference=" + encode(reference) + (owner == null ? "" : "&owner=" + encode(owner));
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
