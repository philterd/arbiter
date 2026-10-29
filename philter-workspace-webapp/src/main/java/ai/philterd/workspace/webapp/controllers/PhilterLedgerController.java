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

import ai.philterd.philter.model.GetLedgerResponse;
import ai.philterd.philter.model.LedgerChain;
import ai.philterd.philter.model.LedgerEntry;
import ai.philterd.philter.model.LedgerExport;
import ai.philterd.philter.model.exceptions.ErrorReasons;
import ai.philterd.philter.model.exceptions.PhilterError;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.service.AuthUtils;
import ai.philterd.workspace.webapp.security.PhilterLedgerService;
import ai.philterd.workspace.webapp.security.PhilterSessionAuthentication;
import ai.philterd.workspace.webapp.security.PhilterSessions;
import ai.philterd.workspace.webapp.ui.Confirmation;
import ai.philterd.workspace.webapp.ui.FormErrors;
import ai.philterd.workspace.webapp.ui.ListView;
import ai.philterd.workspace.webapp.ui.OwnerFilter;
import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Philter's redaction ledger, under Admin: the chains (one per redacted document in a context with the ledger on),
 * a chain's entries and whether it verifies, its export, and deleting it where Philter allows. Called with the
 * signed-in person's session key; an administrator can choose another user's chains, or every user's, with the
 * owner filter, which Philter allows only with {@code ADMIN_CROSS_USER_ACCESS_ENABLED=true}. Batches are redacted
 * with Philter Workspace's own key, so their chains are that key's user's. Philter UI's Ledger page is the
 * reference.
 */
@Controller
public class PhilterLedgerController {

    private static final Logger log = LoggerFactory.getLogger(PhilterLedgerController.class);
    private static final Gson GSON = new Gson();

    static final String PATH = "/admin/ledgers";
    static final String VIEW_PATH = PATH + "/view";

    public static final String CROSS_USER = "Philter found no such user you can reach. Reaching another user's "
            + "ledger needs ADMIN_CROSS_USER_ACCESS_ENABLED=true in Philter.";
    static final String GONE = "Philter has no ledger chain for that document. It may have been deleted.";
    static final String GONE_OR_CROSS_USER = "Philter has no ledger chain for that document and user, or no such user "
            + "you can reach. It may have been deleted; reaching another user's ledger needs "
            + "ADMIN_CROSS_USER_ACCESS_ENABLED=true in Philter.";
    static final String HELD = "A legal hold covers this chain, so Philter refuses to delete it. Release the hold "
            + "under Admin, Legal Holds first.";
    static final String DELETION_DISABLED = "Philter does not allow deleting ledger chains. It needs "
            + "LEDGER_DELETION_ENABLED=true.";
    static final String ADMIN_REQUIRED = "Deleting a ledger chain needs a Philter administrator.";
    // Philter answers 409 without a reason for both causes, so the message names both.
    static final String IN_PROGRESS = "Philter deletes only a completed chain. This one is still being written or "
            + "needs recovery in Philter, or another deletion or hold change for this user's evidence is running or "
            + "needs recovery.";
    static final String UNREADABLE = "An entry in this chain can no longer be read, so Philter does not export it.";

    private final PhilterLedgerService ledger;
    private final AuditLogService auditLogService;

    public PhilterLedgerController(final PhilterLedgerService ledger, final AuditLogService auditLogService) {
        this.ledger = ledger;
        this.auditLogService = auditLogService;
    }

    /** One chain in the list: its first entry, whose it is when not the person's own, and its links. */
    public record Row(LedgerEntry chain, String owner, Confirmation deletion) {
        public String viewUrl() { return VIEW_PATH + query(chain.getDocumentId(), owner); }
    }

    /** How a chain verified, in the words the page shows. */
    public record Verification(boolean valid, String summary, List<String> details) {
    }

    // ---- chains

    @GetMapping(PATH)
    public String list(final Authentication authentication, final HttpServletRequest request, final Model model) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        // No default sort, so Philter's own order applies: the newest chain first.
        ListView list = ListView.of(request, PATH, Set.of("created", "filename"), null, List.of())
                .withOwner(OwnerFilter.from(authentication, request.getParameter("owner"), request.getParameter("all")));
        final OwnerFilter owner = list.owner();
        final List<Row> rows = new ArrayList<>();
        final boolean canDelete = AuthUtils.isAdmin(authentication);
        try {
            // Philter refuses a search across every user's chains, so it is not sent there.
            final GetLedgerResponse page = owner.everyone()
                    ? ledger.chainsAcrossUsers(person.getEncryptedSessionKey(), list.options().search(null))
                    : ledger.chains(person.getEncryptedSessionKey(), owner.username(), list.options());
            if (page.getChains() != null) {
                for (final LedgerEntry chain : page.getChains()) {
                    final String chainOwner = owner.everyone() ? chain.getOwner() : owner.username();
                    rows.add(new Row(chain, chainOwner, deletion(chain.getDocumentId(), chainOwner)));
                }
            }
            list = list.withTotal(page.getTotal());
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            model.addAttribute("error", listError(e, !owner.mine()));
        }
        model.addAttribute("list", list);
        model.addAttribute("rows", rows);
        model.addAttribute("canDelete", canDelete);
        return "admin-ledgers";
    }

    /** A chain's verification and a page of its entries. Philter returns no entries for a chain it cannot check. */
    @GetMapping(VIEW_PATH)
    public String view(@RequestParam("document") final String documentId,
                       @RequestParam(value = "owner", required = false) final String requestedOwner,
                       final Authentication authentication, final HttpServletRequest request, final Model model,
                       final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final OwnerFilter ownerFilter = OwnerFilter.fromOneUser(authentication, requestedOwner);
        final String owner = ownerFilter.username();
        ListView entries = ListView.of(request, VIEW_PATH, Set.of(), null, List.of("document"))
                .withOwner(ownerFilter);
        final LedgerChain chain;
        try {
            chain = ledger.chain(person.getEncryptedSessionKey(), documentId, owner, entries.offset(), entries.limit());
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", actionError(e, owner));
            return "redirect:" + listUrl(owner);
        }
        entries = entries.withTotal(chain.getTotal() == null ? 0 : chain.getTotal());
        model.addAttribute("documentId", documentId);
        model.addAttribute("owner", owner);
        model.addAttribute("verification", verification(chain));
        model.addAttribute("entries", chain.getEntries() == null ? List.of() : chain.getEntries());
        model.addAttribute("list", entries);
        model.addAttribute("backUrl", listUrl(owner));
        model.addAttribute("canWrite", AuthUtils.isAdmin(authentication));
        model.addAttribute("deletion", deletion(documentId, owner));
        model.addAttribute("exportFields", fields(documentId, owner));
        return "admin-ledger-chain";
    }

    /**
     * The chain as Philter exports it, with the original values, as a download. Administrators only (a POST, so
     * SecurityConfig refuses auditors), since it holds personal information; nothing of it is kept or logged.
     */
    @PostMapping(PATH + "/export")
    public ModelAndView export(@RequestParam("document") final String documentId,
                               @RequestParam(value = "owner", required = false) final String requestedOwner,
                               final Authentication authentication, final HttpServletRequest request,
                               final HttpServletResponse response, final RedirectAttributes flash) throws IOException {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return new ModelAndView("redirect:/login");
        }
        final String owner = ownerFor(authentication, requestedOwner);
        final LedgerExport export;
        try {
            export = ledger.export(person.getEncryptedSessionKey(), documentId, owner);
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return new ModelAndView(PhilterSessions.end(request));
            }
            flash.addFlashAttribute("error", e instanceof PhilterError error && error.getStatusCode() == 422
                    ? UNREADABLE : actionError(e, owner));
            return new ModelAndView("redirect:" + VIEW_PATH + query(documentId, owner));
        }
        audit("LEDGER_EXPORT", owner, Map.of("documentId", documentId));
        final byte[] body = GSON.toJson(export).getBytes(StandardCharsets.UTF_8);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName(documentId) + "\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
        return null;
    }

    @GetMapping(PATH + "/delete")
    public String confirmDelete(@RequestParam("document") final String documentId,
                                @RequestParam(value = "owner", required = false) final String requestedOwner,
                                final Authentication authentication, final Model model) {
        model.addAttribute("confirmation", deletion(documentId, ownerFor(authentication, requestedOwner)));
        return "confirm";
    }

    @PostMapping(PATH + "/delete")
    public String delete(@RequestParam("document") final String documentId,
                         @RequestParam(value = "owner", required = false) final String requestedOwner,
                         final Authentication authentication, final HttpServletRequest request,
                         final RedirectAttributes flash) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        final String owner = ownerFor(authentication, requestedOwner);
        try {
            ledger.delete(person.getEncryptedSessionKey(), documentId, owner);
            audit("LEDGER_DELETE", owner, Map.of("documentId", documentId));
            flash.addFlashAttribute("success", "Ledger chain for document " + documentId + " deleted.");
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            flash.addFlashAttribute("error", deleteError(e, owner));
            return "redirect:" + VIEW_PATH + query(documentId, owner);
        }
        return "redirect:" + listUrl(owner);
    }

    static Confirmation deletion(final String documentId, final String owner) {
        return new Confirmation("Delete the ledger chain for document " + documentId + "?",
                "Every entry in it is deleted, and it can no longer be verified or exported. Philter refuses while "
                        + "a legal hold covers it. This cannot be undone.",
                List.of(), PATH + "/delete", "Delete", PATH + "/delete" + query(documentId, owner), listUrl(owner),
                fields(documentId, owner));
    }

    // ---- helpers

    /**
     * What the chain's verification says, from Philter's two checks: every entry matches its hash and links to the
     * one before (the hash chain), and every entry carries a signature that verifies against Philter's signing keys.
     * Philter signs every entry it writes, so an unsigned entry fails too. Philter's API does not name the entry
     * that failed; its log does for the hash chain.
     */
    static Verification verification(final LedgerChain chain) {
        if (chain.getValidationError() != null) {
            return new Verification(false, "Philter could not check this chain.", List.of(
                    "An entry can no longer be read, so the chain cannot be verified and its entries are not shown."));
        }
        final List<String> details = new ArrayList<>();
        details.add(Boolean.FALSE.equals(chain.getHashChainValid())
                ? "Hash chain: broken. An entry does not match its hash, or does not link to the entry before it. "
                        + "Philter's log names the first such entry and its position."
                : "Hash chain: intact. Every entry matches its hash and links to the entry before it.");
        final int signed = chain.getSignedEntries() == null ? 0 : chain.getSignedEntries();
        final int unsigned = chain.getUnsignedEntries() == null ? 0 : chain.getUnsignedEntries();
        if (unsigned > 0) {
            details.add("Signatures: " + unsigned + " of " + (signed + unsigned) + " entries have none. Philter "
                    + "signs every entry it writes, so an entry without one fails.");
        } else if (Boolean.FALSE.equals(chain.getSignaturesValid())) {
            details.add("Signatures: at least one of the " + signed + " does not verify against Philter's signing "
                    + "keys.");
        } else {
            details.add("Signatures: all " + signed + " verify against Philter's signing keys.");
        }
        return new Verification(chain.isValid(), chain.isValid() ? "The chain verifies." : "The chain does not verify.",
                details);
    }

    /** A download name made only of safe characters, whatever the document ID holds. */
    static String fileName(final String documentId) {
        final String safe = documentId.replaceAll("[^A-Za-z0-9._-]", "_");
        return "ledger-" + (safe.length() > 100 ? safe.substring(0, 100) : safe) + ".json";
    }

    /**
     * Records a ledger action in Philter Workspace's audit log. The document ID is Philter's identifier, not
     * personal information; the export itself is never recorded.
     */
    private void audit(final String action, final String owner, final Map<String, Object> details) {
        final Map<String, Object> all = new LinkedHashMap<>(details);
        all.put("method", "philter");
        all.put("owner", owner == null ? "self" : owner);
        auditLogService.log(action, "Ledger", null, all);
    }

    private static String deleteError(final Exception e, final String owner) {
        if (e instanceof PhilterError error) {
            switch (error.getStatusCode()) {
                case 423:
                    return HELD;
                case 404:
                    return owner == null ? GONE : GONE_OR_CROSS_USER;
                case 409:
                    return IN_PROGRESS;
                case 403:
                    return ErrorReasons.FEATURE_DISABLED.equals(error.getReason()) ? DELETION_DISABLED : ADMIN_REQUIRED;
                default:
                    break;
            }
        }
        return FormErrors.from(e).message();
    }

    private static String actionError(final Exception e, final String owner) {
        if (e instanceof PhilterError error && error.getStatusCode() == 404) {
            return owner == null ? GONE : GONE_OR_CROSS_USER;
        }
        return FormErrors.from(e).message();
    }

    private static String listError(final Exception e, final boolean otherUser) {
        if (otherUser && e instanceof PhilterError error && error.getStatusCode() == 404) {
            return CROSS_USER;
        }
        if (e instanceof PhilterError error && error.getStatusCode() == 403) {
            return "Philter refused: your Philter account cannot read the ledger.";
        }
        log.warn("Could not list Philter ledger chains: {}", PhilterClients.describe(e));
        return "Philter could not be reached, so ledger chains cannot be listed.";
    }

    private static Map<String, String> fields(final String documentId, final String owner) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("document", documentId);
        if (owner != null) {
            fields.put("owner", owner);
        }
        return fields;
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

    private static String query(final String documentId, final String owner) {
        return "?document=" + encode(documentId) + (owner == null ? "" : "&owner=" + encode(owner));
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
