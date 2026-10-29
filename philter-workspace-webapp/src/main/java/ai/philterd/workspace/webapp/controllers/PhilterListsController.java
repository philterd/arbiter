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

import ai.philterd.philter.model.CustomList;
import ai.philterd.philter.model.CustomListSummary;
import ai.philterd.philter.model.Page;
import ai.philterd.philter.model.RedactList;
import ai.philterd.philter.model.RedactListTerms;
import ai.philterd.philter.model.exceptions.ErrorReasons;
import ai.philterd.philter.model.exceptions.PhilterError;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.webapp.security.PhilterListsService;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Philter's custom lists, and its always and never redact lists, managed with the signed-in person's session
 * key, so Philter's per-user ownership applies. An administrator can choose another user's lists with the
 * owner filter (and every user's custom lists), which Philter allows only with
 * {@code ADMIN_CROSS_USER_ACCESS_ENABLED=true}. Batches are redacted with Philter Workspace's own key, so they
 * use that key's user's lists. Philter UI's Custom Lists and Redact Lists pages are the reference.
 */
@Controller
public class PhilterListsController {

    private static final Logger log = LoggerFactory.getLogger(PhilterListsController.class);

    static final String PATH = "/policies/lists";
    static final String REDACT_PATH = "/policies/redact-lists";

    // Philter 4.0's limits (CustomListDataService and RedactListsDataService), checked here so the form can
    // say which item is wrong; Philter enforces them too.
    static final int MAXIMUM_ITEMS = 100;
    static final int MAXIMUM_ITEM_LENGTH = 50;
    static final int MAXIMUM_DESCRIPTION_LENGTH = 200;
    static final int MAXIMUM_TERMS = 1000;
    static final int MAXIMUM_TERM_LENGTH = 100;

    public static final String CROSS_USER = "Philter found no such user you can reach. Reaching another user's lists "
            + "needs ADMIN_CROSS_USER_ACCESS_ENABLED=true in Philter.";
    static final String REDACT_LIST_CHANGED = "Someone changed this list after you opened it, so your edits were "
            + "not saved. The list now shows Philter's current terms, and your edits are below it: apply what you "
            + "need to the current list and save again.";

    private static final Map<String, String> REASONS = Map.of(
            ErrorReasons.LIST_EXISTS, "A list with that name already exists.");
    private static final Map<String, String> FIELDS = Map.of("name", "Choose another name.");

    private final PhilterListsService lists;

    public PhilterListsController(final PhilterListsService lists) {
        this.lists = lists;
    }

    /** One custom list in the list, with whose it is when not the person's own, and its links. */
    public record Row(CustomListSummary list, String owner, Confirmation deletion) {
        Row(final CustomListSummary list, final String owner) {
            this(list, owner, PhilterListsController.deletion(list.getName(), owner));
        }

        public String editUrl() { return PATH + "/edit" + query(list.getName(), owner); }
    }

    /** The form state the new and edit pages render, kept when a save is refused. */
    public record ListForm(String name, String owner, String description, String items, String originalDescription,
                           boolean existing) {
    }

    /** One redact list as the page shows it: its terms, one per line, and the revision they were read at. */
    public record RedactListForm(String list, String title, String effect, String terms, long revision, int count) {
    }

    // ---- custom lists

    @GetMapping(PATH)
    public String list(final Authentication authentication, final HttpServletRequest request, final Model model) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        ListView list = ListView.of(request, PATH, Set.of("name"), "name", List.of())
                .withOwner(OwnerFilter.from(authentication, request.getParameter("owner"), request.getParameter("all")));
        final OwnerFilter owner = list.owner();
        final List<Row> rows = new ArrayList<>();
        try {
            final Page<CustomListSummary> page = owner.everyone()
                    ? lists.listAcrossUsers(person.getEncryptedSessionKey(), list.options())
                    : lists.list(person.getEncryptedSessionKey(), owner.username(), list.options());
            page.getItems().forEach(l -> rows.add(new Row(l, owner.everyone() ? l.getOwner() : owner.username())));
            list = list.withTotal(page.getTotal());
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            model.addAttribute("error", listError(e, !owner.mine(), "custom lists"));
        }
        model.addAttribute("activeTab", "lists");
        model.addAttribute("list", list);
        model.addAttribute("rows", rows);
        model.addAttribute("canWrite", AuthUtils.isAdmin(authentication));
        model.addAttribute("newUrl", PATH + "/new" + (owner.username() == null ? "" : "?owner=" + encode(owner.username())));
        return "lists";
    }

    @GetMapping(PATH + "/new")
    public String newList(@RequestParam(value = "owner", required = false) final String requestedOwner,
                          final Authentication authentication, final Model model) {
        return form(model, new ListForm("", ownerFor(authentication, requestedOwner), "", "", "", false), null,
                AuthUtils.isAdmin(authentication));
    }

    @PostMapping(PATH + "/new")
    public String create(@RequestParam("name") final String name,
                         @RequestParam(value = "description", defaultValue = "") final String description,
                         @RequestParam(value = "items", defaultValue = "") final String items,
                         @RequestParam(value = "owner", required = false) final String requestedOwner,
                         final Authentication authentication, final HttpServletRequest request, final Model model,
                         final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        final String trimmed = name == null ? "" : name.trim();
        final ListForm form = new ListForm(trimmed, owner, description, items, "", false);
        final String nameProblem = PhilterNames.problem(trimmed, "list");
        if (nameProblem != null) {
            return form(model, form, new FormErrors(nameProblem, Map.of("name", nameProblem)), true);
        }
        final List<String> values = terms(items);
        final FormErrors problem = itemsProblem(description, values);
        if (problem != null) {
            return form(model, form, problem, true);
        }
        try {
            lists.create(person.getEncryptedSessionKey(), trimmed, blankToNull(description.trim()), values, owner);
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            return form(model, form, saveErrors(e, owner), true);
        }
        flash.addFlashAttribute("success", "List \"" + trimmed + "\" created.");
        return "redirect:" + listUrl(owner);
    }

    /** A list's items and description, editable by administrators and read-only for anyone else. */
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
        final CustomList list;
        try {
            list = lists.get(person.getEncryptedSessionKey(), name, owner);
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", actionError(e, owner));
            return "redirect:" + listUrl(owner);
        }
        final String description = list.getDescription() == null ? "" : list.getDescription();
        final String items = list.getItems() == null ? "" : String.join("\n", list.getItems());
        return form(model, new ListForm(name, owner, description, items, description, true), null,
                AuthUtils.isAdmin(authentication));
    }

    @PostMapping(PATH + "/edit")
    public String save(@RequestParam("name") final String name,
                       @RequestParam(value = "description", defaultValue = "") final String description,
                       @RequestParam(value = "originalDescription", defaultValue = "") final String originalDescription,
                       @RequestParam(value = "items", defaultValue = "") final String items,
                       @RequestParam(value = "owner", required = false) final String requestedOwner,
                       final Authentication authentication, final HttpServletRequest request, final Model model,
                       final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        final ListForm form = new ListForm(name, owner, description, items, originalDescription, true);
        final List<String> values = terms(items);
        final FormErrors problem = itemsProblem(description, values);
        if (problem != null) {
            return form(model, form, problem, true);
        }
        // Philter keeps the description when none is sent, so it is sent only when it changed.
        final String newDescription = description.trim().equals(originalDescription.trim()) ? null : description.trim();
        try {
            lists.replace(person.getEncryptedSessionKey(), name, newDescription, values, owner);
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            return form(model, form, saveErrors(e, owner), true);
        }
        flash.addFlashAttribute("success", "List \"" + name + "\" saved.");
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
            lists.delete(person.getEncryptedSessionKey(), name, owner);
            flash.addFlashAttribute("success", "List \"" + name + "\" deleted.");
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", actionError(e, owner));
        }
        return "redirect:" + listUrl(owner);
    }

    static Confirmation deletion(final String name, final String owner) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", name);
        if (owner != null) {
            fields.put("owner", owner);
        }
        return new Confirmation("Delete the list " + name + "?",
                "Redaction with a policy that refers to it as list:" + name + " fails until the policy no longer "
                        + "does. This cannot be undone.",
                List.of(), PATH + "/delete", "Delete", PATH + "/delete" + query(name, owner), listUrl(owner), fields);
    }

    // ---- always and never redact lists

    /** Both redact lists, the person's own or, for an administrator, one user's. */
    @GetMapping(REDACT_PATH)
    public String redactLists(@RequestParam(value = "owner", required = false) final String requestedOwner,
                              final Authentication authentication, final HttpServletRequest request,
                              final Model model) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        // Philter reads redact lists one owner at a time.
        final OwnerFilter owner = OwnerFilter.fromOneUser(authentication, requestedOwner);
        try {
            final Map<RedactList, RedactListForm> forms = new LinkedHashMap<>();
            for (final RedactList list : RedactList.values()) {
                forms.put(list, redactListForm(list, lists.getRedactList(person.getEncryptedSessionKey(), list,
                        owner.username())));
            }
            model.addAttribute("redactLists", forms.values());
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            model.addAttribute("error", listError(e, owner.username() != null, "the redact lists"));
        }
        return redactPage(model, authentication, owner);
    }

    /**
     * Replaces one redact list, only if it is still at the revision the page read. If someone changed it since,
     * nothing is written and the page shows Philter's current list with the person's edits beside it.
     */
    @PostMapping(REDACT_PATH)
    public String replaceRedactList(@RequestParam("list") final String listName,
                                    @RequestParam(value = "terms", defaultValue = "") final String terms,
                                    @RequestParam("revision") final long revision,
                                    @RequestParam(value = "owner", required = false) final String requestedOwner,
                                    final Authentication authentication, final HttpServletRequest request,
                                    final Model model, final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final OwnerFilter owner = OwnerFilter.fromOneUser(authentication, requestedOwner);
        final RedactList list = redactList(listName);
        if (list == null) {
            return "redirect:" + redactUrl(owner.username());
        }
        final String key = person.getEncryptedSessionKey();
        final List<String> values = terms(terms);
        final String problem = termsProblem(values);
        FormErrors errors = problem == null ? null : FormErrors.of(problem);
        boolean changed = false;
        if (errors == null) {
            try {
                lists.replaceRedactList(key, list, values, revision, owner.username());
                flash.addFlashAttribute("success", title(list) + " saved.");
                return "redirect:" + redactUrl(owner.username());
            } catch (Exception e) {
                if (e instanceof UnauthorizedException) {
                    return PhilterSessions.end(request);
                }
                changed = e instanceof PhilterError error && ErrorReasons.REDACT_LIST_CHANGED.equals(error.getReason());
                errors = changed ? FormErrors.of(REDACT_LIST_CHANGED) : saveErrors(e, owner.username());
            }
        }
        // The page again: Philter's lists as they are now, except that a refused list keeps the person's edits
        // unless it changed in Philter, when they are shown beside it instead.
        try {
            final List<RedactListForm> forms = new ArrayList<>();
            for (final RedactList each : RedactList.values()) {
                final RedactListForm current = redactListForm(each, lists.getRedactList(key, each, owner.username()));
                forms.add(each == list && !changed
                        ? new RedactListForm(current.list(), current.title(), current.effect(), terms, revision,
                                current.count())
                        : current);
            }
            model.addAttribute("redactLists", forms);
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            model.addAttribute("error", listError(e, owner.username() != null, "the redact lists"));
        }
        model.addAttribute("errors", errors);
        model.addAttribute("errorList", list.getName());
        if (changed) {
            model.addAttribute("unsaved", terms);
        }
        return redactPage(model, authentication, owner);
    }

    private String redactPage(final Model model, final Authentication authentication, final OwnerFilter owner) {
        model.addAttribute("activeTab", "redact-lists");
        model.addAttribute("ownerFilter", owner);
        model.addAttribute("canWrite", AuthUtils.isAdmin(authentication));
        model.addAttribute("maximumTerms", MAXIMUM_TERMS);
        model.addAttribute("maximumTermLength", MAXIMUM_TERM_LENGTH);
        return "redact-lists";
    }

    private static RedactListForm redactListForm(final RedactList list, final RedactListTerms read) {
        final List<String> terms = read.getTerms() == null ? List.of() : read.getTerms();
        return new RedactListForm(list.getName(), title(list),
                list == RedactList.ALWAYS ? "are always redacted, whatever the policy"
                        : "are never redacted, whatever the policy",
                String.join("\n", terms), read.getRevision(), terms.size());
    }

    private static String title(final RedactList list) {
        return list == RedactList.ALWAYS ? "Always redact list" : "Never redact list";
    }

    private static RedactList redactList(final String name) {
        return Arrays.stream(RedactList.values()).filter(l -> l.getName().equals(name)).findFirst().orElse(null);
    }

    // ---- validation

    /** The lines of a text area, trimmed, without blank ones, as Philter keeps them. */
    static List<String> terms(final String text) {
        final List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (final String line : text.split("\\r?\\n")) {
            final String term = line.trim();
            if (!term.isEmpty()) {
                out.add(term);
            }
        }
        return out;
    }

    /** What is wrong with a custom list's description and items under Philter's limits, or null. */
    static FormErrors itemsProblem(final String description, final List<String> items) {
        if (description != null && description.trim().length() > MAXIMUM_DESCRIPTION_LENGTH) {
            final String message = "The description can be at most " + MAXIMUM_DESCRIPTION_LENGTH + " characters.";
            return new FormErrors(message, Map.of("description", message));
        }
        final String problem = items.isEmpty() ? "Enter at least one item."
                : items.size() > MAXIMUM_ITEMS
                        ? "A list can have at most " + MAXIMUM_ITEMS + " items; this one has " + items.size() + "."
                        : tooLong(items, MAXIMUM_ITEM_LENGTH, "Item");
        return problem == null ? null : new FormErrors(problem, Map.of("items", problem));
    }

    /** What is wrong with a redact list's terms under Philter's limits, or null. An empty list clears it. */
    static String termsProblem(final List<String> terms) {
        if (terms.size() > MAXIMUM_TERMS) {
            return "A list can have at most " + MAXIMUM_TERMS + " terms; this one has " + terms.size() + ".";
        }
        return tooLong(terms, MAXIMUM_TERM_LENGTH, "Term");
    }

    /** Names the first entry over {@code maximum} characters by its position, not its text. */
    private static String tooLong(final List<String> values, final int maximum, final String noun) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).length() > maximum) {
                return noun + " " + (i + 1) + " is longer than " + maximum + " characters.";
            }
        }
        return null;
    }

    // ---- errors

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
        return FormErrors.from(e).message();
    }

    private static String listError(final Exception e, final boolean otherUser, final String what) {
        if (otherUser && e instanceof PhilterError error && error.getStatusCode() == 404) {
            return CROSS_USER;
        }
        if (e instanceof PhilterError error && error.getStatusCode() == 403) {
            return "Philter refused: your Philter account cannot read lists.";
        }
        log.warn("Could not read Philter's lists: {}", PhilterClients.describe(e));
        return "Philter could not be reached, so " + what + " cannot be shown.";
    }

    // ---- helpers

    private String form(final Model model, final ListForm form, final FormErrors errors, final boolean canWrite) {
        model.addAttribute("form", form);
        model.addAttribute("errors", errors);
        model.addAttribute("canWrite", canWrite);
        model.addAttribute("maximumItems", MAXIMUM_ITEMS);
        model.addAttribute("maximumItemLength", MAXIMUM_ITEM_LENGTH);
        model.addAttribute("maximumDescriptionLength", MAXIMUM_DESCRIPTION_LENGTH);
        model.addAttribute("backUrl", listUrl(form.owner()));
        return "list-form";
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

    static String redactUrl(final String owner) {
        return owner == null ? REDACT_PATH : REDACT_PATH + "?owner=" + encode(owner);
    }

    private static String query(final String name, final String owner) {
        return "?name=" + encode(name) + (owner == null ? "" : "&owner=" + encode(owner));
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
