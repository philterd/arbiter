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

import ai.philterd.philter.model.DeletedPolicy;
import ai.philterd.philter.model.GetDeletedPoliciesResponse;
import ai.philterd.philter.model.Page;
import ai.philterd.philter.model.PolicyDetails;
import ai.philterd.philter.model.PolicyDiff;
import ai.philterd.philter.model.PolicySummary;
import ai.philterd.philter.model.PolicyVersionSummary;
import ai.philterd.philter.model.PolicyWithETag;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.philter.model.exceptions.PhilterError;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.service.GeneralSettingsService;
import ai.philterd.workspace.service.PhilterSettings;
import ai.philterd.workspace.webapp.security.PhilterPoliciesService;
import ai.philterd.workspace.webapp.security.PhilterSessionAuthentication;
import ai.philterd.workspace.webapp.security.PhilterSessions;
import ai.philterd.workspace.webapp.ui.Confirmation;
import ai.philterd.workspace.webapp.ui.FormErrors;
import ai.philterd.workspace.webapp.ui.JsonInput;
import ai.philterd.workspace.webapp.ui.ListView;
import ai.philterd.workspace.webapp.ui.OwnerFilter;
import com.google.gson.JsonElement;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Philter's redaction policies, managed through its Policies API with the signed-in person's session key, so
 * Philter's per-user ownership applies. An administrator can choose another user's policies, or everyone's,
 * with the owner filter, which Philter allows only with {@code ADMIN_CROSS_USER_ACCESS_ENABLED=true}.
 * Batches are redacted with Philter Workspace's own key, so they use that key's user's policies, listed
 * read-only under "Used by batches". Philter UI's Policies page and history dialog are the reference.
 */
@Controller
public class PhilterPoliciesController {

    private static final Logger log = LoggerFactory.getLogger(PhilterPoliciesController.class);

    static final String PATH = "/policies";
    private static final String REDIRECT = "redirect:" + PATH;
    /** Philter's starting point for a new policy. */
    static final String TEMPLATE = "default";
    static final String TEMPLATE_UNAVAILABLE = "Philter's policy template could not be read, so the policy starts "
            + "empty. Add identifiers, or build it in the Policy Editor.";
    static final String CROSS_USER = "Philter found no such user you can reach. Reaching another user's policies "
            + "needs ADMIN_CROSS_USER_ACCESS_ENABLED=true in Philter.";
    static final String BACKGROUND_KEY_REJECTED = "Philter rejected PHILTER_WORKSPACE_PHILTER_API_KEY, so the "
            + "policies batches use cannot be listed. See Admin > Philter.";
    static final Set<String> VIEWS = Set.of("mine", "managed", "deleted", "batches");

    private static final Map<String, String> REASONS = Map.of(
            "policy_exists", "A policy with that name already exists.",
            "policy_managed", "Managed policies cannot be changed. Create a policy from it instead.",
            "policy_default", "The default policy cannot be deleted.");
    private static final Map<String, String> FIELDS = Map.of("name", "Choose another name.");

    private final PhilterPoliciesService policies;
    private final PhilterSettings philter;
    private final GeneralSettingsService generalSettingsService;

    public PhilterPoliciesController(final PhilterPoliciesService policies, final PhilterSettings philter,
                                     final GeneralSettingsService generalSettingsService) {
        this.policies = policies;
        this.philter = philter;
        this.generalSettingsService = generalSettingsService;
    }

    /**
     * One row of a list: the policy, whose it is when not the person's own, its delete confirmation, and its
     * links, built here so a null owner is left out rather than sent empty.
     */
    public record Row(PolicySummary policy, String owner, Confirmation deletion) {
        Row(final PolicySummary policy, final String owner) {
            this(policy, owner, PhilterPoliciesController.deletion(policy.getName(), owner));
        }

        public String viewUrl() { return PATH + "/view" + query(policy.getName(), owner, null); }
        public String editUrl() { return PATH + "/edit" + query(policy.getName(), owner, null); }
        public String copyUrl() { return PATH + "/copy" + query(policy.getName(), owner, null); }
        public String historyUrl() { return PhilterPoliciesController.historyUrl(policy.getName(), owner, false); }
    }

    /** One deleted policy: its history and the confirmation to restore it as it was when it was deleted. */
    public record DeletedRow(DeletedPolicy policy, String owner, Confirmation restore) {
        DeletedRow(final DeletedPolicy policy, final String owner) {
            this(policy, owner, PhilterPoliciesController.restore(policy.getName(), policy.getLatestRevision(), owner));
        }

        public String historyUrl() { return PhilterPoliciesController.historyUrl(policy.getName(), owner, true); }
    }

    /**
     * The Redaction Policies tab of the Policies page: the person's policies (or, for an administrator, one
     * user's or everyone's), Philter's managed policies, deleted policies, and the policies batches use. Each
     * list has its own path, so the filter bar's Clear keeps the list.
     */
    @GetMapping({PATH, PATH + "/managed", PATH + "/deleted", PATH + "/batches"})
    public String list(@RequestParam(value = "tab", required = false) final String tab,
                       final Authentication authentication, final HttpServletRequest request, final Model model) {
        if ("finalization".equals(tab)) {
            return "redirect:" + PolicyController.FINALIZATION_PATH;
        }
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String path = request.getRequestURI().substring(request.getContextPath().length());
        final String requested = path.startsWith(PATH + "/") ? path.substring(PATH.length() + 1) : "mine";
        final String view = VIEWS.contains(requested) ? requested : "mine";
        final String key = person.getEncryptedSessionKey();
        ListView list = ListView.of(request, "mine".equals(view) ? PATH : PATH + "/" + view, sortsFor(view), "name",
                List.of());
        if ("mine".equals(view)) {
            list = list.withOwner(OwnerFilter.from(authentication, request.getParameter("owner"),
                    request.getParameter("all")));
        } else if ("deleted".equals(view)) {
            // Philter lists deleted policies one owner at a time.
            list = list.withOwner(OwnerFilter.fromOneUser(authentication, request.getParameter("owner")));
        }
        final OwnerFilter owner = list.owner();
        final List<Row> rows = new ArrayList<>();
        final List<DeletedRow> deleted = new ArrayList<>();
        try {
            switch (view) {
                case "managed" -> {
                    final Page<PolicySummary> page = policies.listManaged(key, list.options());
                    page.getItems().forEach(p -> rows.add(new Row(p, null)));
                    list = list.withTotal(page.getTotal());
                }
                case "deleted" -> {
                    final GetDeletedPoliciesResponse page = policies.listDeleted(key, owner.username(), list.options());
                    if (page.getPolicies() != null) {
                        page.getPolicies().forEach(d -> deleted.add(new DeletedRow(d, owner.username())));
                    }
                    list = list.withTotal(page.getTotal());
                }
                case "batches" -> {
                    // Philter Workspace's own key: these are the policies batches are redacted with.
                    final Page<PolicySummary> page = philter.client().listPolicies(null, list.options());
                    page.getItems().forEach(p -> rows.add(new Row(p, null)));
                    list = list.withTotal(page.getTotal());
                }
                default -> {
                    final Page<PolicySummary> page = owner.everyone()
                            ? policies.listAcrossUsers(key, list.options())
                            : policies.list(key, owner.username(), list.options());
                    page.getItems().forEach(p -> rows.add(new Row(p, owner.everyone() ? p.getOwner() : owner.username())));
                    list = list.withTotal(page.getTotal());
                }
            }
        } catch (Exception e) {
            if (e instanceof UnauthorizedException && !"batches".equals(view)) {
                return PhilterSessions.end(request);
            }
            model.addAttribute("error", "batches".equals(view) && e instanceof UnauthorizedException
                    ? BACKGROUND_KEY_REJECTED : listError(e, owner));
        }
        model.addAttribute("activeTab", "redaction");
        model.addAttribute("view", view);
        model.addAttribute("list", list);
        model.addAttribute("rows", rows);
        model.addAttribute("deleted", deleted);
        model.addAttribute("canWrite", AuthUtils.isAdmin(authentication));
        model.addAttribute("newUrl", PATH + "/new" + (owner.username() == null ? "" : "?owner=" + encode(owner.username())));
        return "policies";
    }

    @GetMapping(PATH + "/new")
    public String newPolicy(final Authentication authentication, final HttpServletRequest request,
                            final Model model) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        String json = null;
        try {
            final String template = policies.template(person.getEncryptedSessionKey(), TEMPLATE);
            if (JsonInput.objectError(template) == null) {
                json = JsonInput.pretty(template);
            }
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            log.warn("Could not read Philter's policy template: {}", PhilterClients.describe(e));
        }
        if (json == null) {
            model.addAttribute("warning", TEMPLATE_UNAVAILABLE);
            json = "{\n  \"identifiers\": {\n  }\n}";
        }
        return form(model, new PolicyForm(null, null, json, "", "", null, "", ""), null, null);
    }

    @PostMapping(PATH + "/new")
    public String create(@RequestParam("name") final String name, @RequestParam("json") final String json,
                         @RequestParam(value = "description", defaultValue = "") final String description,
                         @RequestParam(value = "notes", defaultValue = "") final String notes,
                         @RequestParam(value = "owner", required = false) final String requestedOwner,
                         final Authentication authentication, final HttpServletRequest request, final Model model,
                         final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        final String trimmed = name == null ? "" : name.trim();
        final PolicyForm form = new PolicyForm(trimmed, owner, json, description, notes, null, "", "");
        if (trimmed.isEmpty()) {
            return form(model, form, new FormErrors("Enter a name.", Map.of("name", "Enter a name.")), null);
        }
        final String jsonError = JsonInput.objectError(json);
        if (jsonError != null) {
            return form(model, form, null, jsonError);
        }
        try {
            policies.create(person.getEncryptedSessionKey(), trimmed, json, blankToNull(description),
                    blankToNull(lines(notes)), owner);
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            return form(model, form, saveErrors(e, owner), null);
        }
        flash.addFlashAttribute("success", "Policy \"" + trimmed + "\" created.");
        return redirectToList(owner);
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
        final PolicyWithETag policy;
        final PolicyDetails details;
        try {
            // The tag is read first, so a change between the two reads makes the save refuse, not overwrite.
            policy = policies.getWithETag(person.getEncryptedSessionKey(), name, owner);
            details = policies.details(person.getEncryptedSessionKey(), name, owner);
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", readError(e, owner));
            return redirectToList(owner);
        }
        final String description = nullToEmpty(details.getDescription());
        final String notes = nullToEmpty(details.getNotes());
        return form(model, new PolicyForm(name, owner, JsonInput.pretty(policy.getJson()), description, notes,
                policy.getETag(), description, notes), null, null);
    }

    @PostMapping(PATH + "/edit")
    public String save(@RequestParam("name") final String name, @RequestParam("json") final String json,
                       @RequestParam(value = "description", defaultValue = "") final String description,
                       @RequestParam(value = "notes", defaultValue = "") final String notes,
                       @RequestParam("eTag") final String eTag,
                       @RequestParam(value = "originalDescription", defaultValue = "") final String originalDescription,
                       @RequestParam(value = "originalNotes", defaultValue = "") final String originalNotes,
                       @RequestParam(value = "owner", required = false) final String requestedOwner,
                       final Authentication authentication, final HttpServletRequest request, final Model model,
                       final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        final PolicyForm form = new PolicyForm(name, owner, json, description, lines(notes), eTag,
                originalDescription, lines(originalNotes));
        final String jsonError = JsonInput.objectError(json);
        if (jsonError != null) {
            return form(model, form, null, jsonError);
        }
        // Only what changed is sent; Philter adds a revision only when the JSON changed.
        final String newDescription = description.equals(originalDescription) ? null : description;
        // Browsers send a text area's line breaks as CRLF, so compare and send them as Philter keeps them.
        final String newNotes = lines(notes).equals(lines(originalNotes)) ? null : lines(notes);
        try {
            policies.replace(person.getEncryptedSessionKey(), name, json, newDescription, newNotes, eTag, owner);
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            return form(model, form, saveErrors(e, owner), null);
        }
        flash.addFlashAttribute("success", "Policy \"" + name + "\" saved.");
        return redirectToList(owner);
    }

    /** A policy's JSON, read-only: the person's own, another user's, a managed one, or one revision. */
    @GetMapping(PATH + "/view")
    public String view(@RequestParam("name") final String name,
                       @RequestParam(value = "owner", required = false) final String requestedOwner,
                       @RequestParam(value = "revision", required = false) final Integer revision,
                       @RequestParam(value = "batches", defaultValue = "false") final boolean batches,
                       final Authentication authentication, final HttpServletRequest request, final Model model,
                       final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        final String json;
        try {
            if (batches) {
                json = philter.client().getPolicy(name);
            } else if (revision != null) {
                json = policies.version(person.getEncryptedSessionKey(), name, revision, owner);
            } else {
                json = policies.get(person.getEncryptedSessionKey(), name, owner);
            }
        } catch (Exception e) {
            if (e instanceof UnauthorizedException && !batches) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", readError(e, owner));
            return redirectToList(owner);
        }
        model.addAttribute("name", name);
        model.addAttribute("owner", owner);
        model.addAttribute("revision", revision);
        model.addAttribute("json", JsonInput.pretty(json));
        return "policy-view";
    }

    /** The name form for duplicating a policy, or creating one from a managed policy. */
    @GetMapping(PATH + "/copy")
    public String copyForm(@RequestParam("name") final String source,
                           @RequestParam(value = "owner", required = false) final String requestedOwner,
                           final Authentication authentication, final Model model) {
        model.addAttribute("source", source);
        model.addAttribute("owner", ownerFor(authentication, requestedOwner));
        model.addAttribute("newName", "");
        return "policy-copy";
    }

    @PostMapping(PATH + "/copy")
    public String copy(@RequestParam("source") final String source, @RequestParam("name") final String name,
                       @RequestParam(value = "owner", required = false) final String requestedOwner,
                       final Authentication authentication, final HttpServletRequest request, final Model model,
                       final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        final String trimmed = name == null ? "" : name.trim();
        FormErrors errors = trimmed.isEmpty() ? new FormErrors("Enter a name.", Map.of("name", "Enter a name.")) : null;
        if (errors == null) {
            try {
                policies.copy(person.getEncryptedSessionKey(), source, trimmed, owner);
                flash.addFlashAttribute("success", "Policy \"" + trimmed + "\" created from \"" + source + "\".");
                return redirectToList(owner);
            } catch (Exception e) {
                if (e instanceof UnauthorizedException) {
                    return PhilterSessions.end(request);
                }
                errors = saveErrors(e, owner);
            }
        }
        model.addAttribute("source", source);
        model.addAttribute("owner", owner);
        model.addAttribute("newName", trimmed);
        model.addAttribute("errors", errors);
        return "policy-copy";
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
            policies.delete(person.getEncryptedSessionKey(), name, owner);
            flash.addFlashAttribute("success", "Policy \"" + name + "\" deleted. Its history is kept under "
                    + "Deleted.");
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", actionError(e, owner, REASONS));
        }
        return redirectToList(owner);
    }

    /** A policy's version history, and with {@code from} and {@code to}, what changed between two revisions. */
    @GetMapping(PATH + "/history")
    public String history(@RequestParam("name") final String name,
                          @RequestParam(value = "owner", required = false) final String requestedOwner,
                          @RequestParam(value = "deleted", defaultValue = "false") final boolean deleted,
                          @RequestParam(value = "from", required = false) final Integer from,
                          @RequestParam(value = "to", required = false) final Integer to,
                          final Authentication authentication, final HttpServletRequest request, final Model model,
                          final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        final String key = person.getEncryptedSessionKey();
        final List<PolicyVersionSummary> versions;
        String eTag = null;
        try {
            // A live policy's tag as the history opened, so a rollback is refused if the policy changed since.
            if (!deleted) {
                eTag = policies.details(key, name, owner).getEtag();
            }
            final Page<PolicyVersionSummary> page = policies.versions(key, name, owner);
            versions = page == null || page.getItems() == null ? List.of() : page.getItems();
            if (from != null && to != null) {
                final PolicyDiff diff = policies.diff(key, name, from, to, owner);
                model.addAttribute("diff", diff);
                model.addAttribute("changes", changes(diff));
            }
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", readError(e, owner));
            return redirectToList(owner);
        }
        final int latest = versions.isEmpty() ? -1 : versions.get(0).getRevision();
        final Map<Integer, Confirmation> actions = new LinkedHashMap<>();
        for (final PolicyVersionSummary version : versions) {
            if (deleted) {
                actions.put(version.getRevision(), restore(name, version.getRevision(), owner));
            } else if (version.getRevision() != latest) {
                actions.put(version.getRevision(), rollback(name, version.getRevision(), eTag, owner));
            }
        }
        model.addAttribute("name", name);
        model.addAttribute("owner", owner);
        model.addAttribute("deleted", deleted);
        model.addAttribute("versions", versions);
        model.addAttribute("latest", latest);
        model.addAttribute("from", from != null ? from : versions.size() > 1 ? versions.get(1).getRevision() : null);
        model.addAttribute("to", to != null ? to : latest > 0 ? latest : null);
        model.addAttribute("actions", actions);
        final Map<Integer, String> revisionUrls = new LinkedHashMap<>();
        versions.forEach(v -> revisionUrls.put(v.getRevision(), PATH + "/view" + query(name, owner, v.getRevision())));
        model.addAttribute("revisionUrls", revisionUrls);
        model.addAttribute("canWrite", AuthUtils.isAdmin(authentication));
        return "policy-history";
    }

    @GetMapping(PATH + "/rollback")
    public String confirmRollback(@RequestParam("name") final String name, @RequestParam("revision") final int revision,
                                  @RequestParam(value = "owner", required = false) final String requestedOwner,
                                  final Authentication authentication, final HttpServletRequest request,
                                  final Model model, final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        try {
            final String eTag = policies.details(person.getEncryptedSessionKey(), name, owner).getEtag();
            model.addAttribute("confirmation", rollback(name, revision, eTag, owner));
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", readError(e, owner));
            return redirectToList(owner);
        }
        return "confirm";
    }

    @PostMapping(PATH + "/rollback")
    public String doRollback(@RequestParam("name") final String name, @RequestParam("revision") final int revision,
                             @RequestParam("eTag") final String eTag,
                             @RequestParam(value = "owner", required = false) final String requestedOwner,
                             final Authentication authentication, final HttpServletRequest request,
                             final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        try {
            final int created = policies.rollback(person.getEncryptedSessionKey(), name, revision, eTag, owner)
                    .getRevision();
            flash.addFlashAttribute("success", "\"" + name + "\" rolled back to revision " + revision
                    + ", saved as revision " + created + ".");
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", actionError(e, owner, REASONS));
        }
        return "redirect:" + historyUrl(name, owner, false);
    }

    @GetMapping(PATH + "/restore")
    public String confirmRestore(@RequestParam("name") final String name, @RequestParam("revision") final int revision,
                                 @RequestParam(value = "owner", required = false) final String requestedOwner,
                                 final Authentication authentication, final Model model) {
        model.addAttribute("confirmation", restore(name, revision, ownerFor(authentication, requestedOwner)));
        return "confirm";
    }

    @PostMapping(PATH + "/restore")
    public String doRestore(@RequestParam("name") final String name, @RequestParam("revision") final int revision,
                            @RequestParam(value = "owner", required = false) final String requestedOwner,
                            final Authentication authentication, final HttpServletRequest request,
                            final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        try {
            policies.restore(person.getEncryptedSessionKey(), name, revision, owner);
            flash.addFlashAttribute("success", "\"" + name + "\" restored from revision " + revision + ".");
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", actionError(e, owner, Map.of("policy_exists",
                    "A policy named \"" + name + "\" exists again, so this one cannot be restored under that name.")));
        }
        return redirectToList(owner);
    }

    // ---- confirmations, shared by the dialogs and the pages a browser without JavaScript opens

    static Confirmation deletion(final String name, final String owner) {
        return new Confirmation("Delete the policy " + name + "?",
                "It can no longer be used for redaction. Philter keeps its version history, so it can be restored.",
                List.of(), PATH + "/delete", "Delete", PATH + "/delete" + query(name, owner, null), listUrl(owner),
                fields(name, owner, null, null));
    }

    static Confirmation rollback(final String name, final int revision, final String eTag, final String owner) {
        return new Confirmation("Roll " + name + " back to revision " + revision + "?",
                "Its content becomes a new revision; no revision is removed. This is recorded in Philter's audit log.",
                List.of(), PATH + "/rollback", "Roll back",
                PATH + "/rollback" + query(name, owner, revision), historyUrl(name, owner, false),
                fields(name, owner, revision, eTag));
    }

    static Confirmation restore(final String name, final int revision, final String owner) {
        return new Confirmation("Restore " + name + " from revision " + revision + "?",
                "It is saved as a policy named " + name + " again, at a new revision, and its history continues.",
                List.of(), PATH + "/restore", "Restore",
                PATH + "/restore" + query(name, owner, revision), listUrl(owner), fields(name, owner, revision, null));
    }



    private static Map<String, String> fields(final String name, final String owner, final Integer revision,
                                              final String eTag) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", name);
        if (owner != null) {
            fields.put("owner", owner);
        }
        if (revision != null) {
            fields.put("revision", Integer.toString(revision));
        }
        if (eTag != null) {
            fields.put("eTag", eTag);
        }
        return fields;
    }

    // ---- helpers

    /** The form state a new or edit page renders, kept when Philter refuses a save. */
    public static final class PolicyForm {
        private final String name;
        private final String owner;
        private final String json;
        private final String description;
        private final String notes;
        private final String eTag;
        private final String originalDescription;
        private final String originalNotes;

        PolicyForm(final String name, final String owner, final String json, final String description,
                   final String notes, final String eTag, final String originalDescription,
                   final String originalNotes) {
            this.name = name;
            this.owner = owner;
            this.json = json;
            this.description = description;
            this.notes = notes;
            this.eTag = eTag;
            this.originalDescription = originalDescription;
            this.originalNotes = originalNotes;
        }

        public String getName() { return name; }
        public String getOwner() { return owner; }
        public String getJson() { return json; }
        public String getDescription() { return description; }
        public String getNotes() { return notes; }
        public String getETag() { return eTag; }
        public String getOriginalDescription() { return originalDescription; }
        public String getOriginalNotes() { return originalNotes; }
        /** An existing policy is edited; a new one is created. */
        public boolean isExisting() { return eTag != null; }
    }

    private String form(final Model model, final PolicyForm form, final FormErrors errors, final String jsonError) {
        model.addAttribute("form", form);
        model.addAttribute("errors", errors);
        model.addAttribute("jsonError", jsonError);
        model.addAttribute("policyEditorUrl", policyEditorUrl());
        return "policy-form";
    }

    /**
     * Why Philter refused a save. When it refused the policy itself (400), its reason is shown: it describes the
     * policy the person just wrote, and is shown only to them. Other refusals use Philter Workspace's messages.
     */
    static FormErrors saveErrors(final Exception e, final String owner) {
        if (e instanceof ClientException client && client.getStatusCode() == 400 && client.getErrorMessage() != null
                && client.getReason() != null && !FormErrors.CHANGED_REASONS.contains(client.getReason())) {
            return FormErrors.of("Philter did not accept the policy: " + client.getErrorMessage());
        }
        if (owner != null && e instanceof PhilterError error && error.getStatusCode() == 404) {
            return FormErrors.of(CROSS_USER);
        }
        return FormErrors.from(e, REASONS, FIELDS);
    }

    private static String readError(final Exception e, final String owner) {
        if (owner != null && e instanceof PhilterError error && error.getStatusCode() == 404) {
            return CROSS_USER;
        }
        return FormErrors.from(e).message();
    }

    /** Why Philter refused a delete, roll back, or restore. */
    private static String actionError(final Exception e, final String owner, final Map<String, String> reasons) {
        if (owner != null && e instanceof PhilterError error && error.getStatusCode() == 404) {
            return CROSS_USER;
        }
        return FormErrors.from(e, reasons, Map.of()).message();
    }

    private static String listError(final Exception e, final OwnerFilter owner) {
        if (!owner.mine() && e instanceof PhilterError error && error.getStatusCode() == 404) {
            return CROSS_USER;
        }
        if (e instanceof PhilterError error && error.getStatusCode() == 403) {
            return "Philter refused: your Philter account cannot read policies.";
        }
        log.warn("Could not list Philter policies: {}", PhilterClients.describe(e));
        return "Philter could not be reached, so policies cannot be listed.";
    }

    /** Philter's diff, one row per change, with the value before (replace, remove) and after (add, replace). */
    static List<Map<String, String>> changes(final PolicyDiff diff) {
        final List<Map<String, String>> out = new ArrayList<>();
        if (diff == null || diff.getChanges() == null) {
            return out;
        }
        for (final PolicyDiff.Change change : diff.getChanges()) {
            final Map<String, String> row = new LinkedHashMap<>();
            row.put("op", change.getOp());
            row.put("path", change.getPath());
            row.put("before", PolicyDiff.Change.ADD.equals(change.getOp()) ? "" : text(change.getOldValue()));
            row.put("after", PolicyDiff.Change.REMOVE.equals(change.getOp()) ? "" : text(change.getValue()));
            out.add(row);
        }
        return out;
    }

    private static String text(final JsonElement element) {
        if (element == null) {
            return "";
        }
        if (element.isJsonNull()) {
            return "null";
        }
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    private static Set<String> sortsFor(final String view) {
        return switch (view) {
            case "mine", "batches" -> Set.of("name", "created", "updated");
            default -> Set.of("name");
        };
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

    private static String redirectToList(final String owner) {
        return owner == null ? REDIRECT : "redirect:" + listUrl(owner);
    }

    static String historyUrl(final String name, final String owner, final boolean deleted) {
        return PATH + "/history" + query(name, owner, null) + (deleted ? "&deleted=true" : "");
    }

    private static String query(final String name, final String owner, final Integer revision) {
        return "?name=" + encode(name) + (owner == null ? "" : "&owner=" + encode(owner))
                + (revision == null ? "" : "&revision=" + revision);
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The Policy Editor that Philter Workspace's compose files run beside it, on port 8081 of its host. */
    private String policyEditorUrl() {
        final String workspaceUrl = generalSettingsService.load().getWorkspaceUrl();
        if (workspaceUrl == null || workspaceUrl.isBlank()) {
            return "http://localhost:8081";
        }
        try {
            final URI uri = URI.create(workspaceUrl.trim());
            final String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            final String host = uri.getHost() == null ? "localhost" : uri.getHost();
            return scheme + "://" + host + ":8081";
        } catch (IllegalArgumentException e) {
            log.debug("Could not parse Philter Workspace URL '{}': {}", workspaceUrl, e.getMessage());
            return "http://localhost:8081";
        }
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String lines(final String value) {
        return value == null ? "" : value.replace("\r\n", "\n");
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }
}
