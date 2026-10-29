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

import ai.philterd.philter.model.exceptions.PhilterError;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import ai.philterd.workspace.philter.PhilterClients;
import ai.philterd.workspace.service.AuditLogService;
import ai.philterd.workspace.webapp.security.PhilterSessionAuthentication;
import ai.philterd.workspace.webapp.security.PhilterSessions;
import ai.philterd.workspace.webapp.security.PhilterTryService;
import ai.philterd.workspace.webapp.ui.FormErrors;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Tries a policy on pasted text or a PDF, outside any batch, with the signed-in person's session key, and lists
 * Philter's models. Nothing tried is stored: the text and the PDF are sent to Philter without a context, so it
 * keeps no replacement and writes no ledger entry, and the result is returned to the browser and not kept. Only
 * the fact of each try (the policy, the kind, and the size) is audited. Philter UI's Dashboard and Models pages
 * are the reference.
 */
@Controller
public class PhilterTryController {

    private static final Logger log = LoggerFactory.getLogger(PhilterTryController.class);

    static final String PATH = "/try";
    static final String MODELS_PATH = "/policies/models";

    /** The servlet container's default limit on a form post (Tomcat's maxPostSize). */
    static final long MAXIMUM_FORM_BYTES = 2L * 1024 * 1024;

    static final String NO_POLICY = "Philter has no policy of yours with that name.";
    static final String CANNOT_APPLY = "Philter could not redact with this policy. A policy that refers to a custom "
            + "list you do not have, or that Philter cannot load, cannot be used.";

    private final PhilterTryService philter;
    private final AuditLogService auditLogService;

    public PhilterTryController(final PhilterTryService philter, final AuditLogService auditLogService) {
        this.philter = philter;
        this.auditLogService = auditLogService;
    }

    @GetMapping(PATH)
    public String page(final Authentication authentication, final HttpServletRequest request, final Model model) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        return page(person, request, model, "default", "");
    }

    /** Redacts the text and shows the result beside it; neither is kept. */
    @PostMapping(PATH + "/text")
    public String text(@RequestParam(value = "policy", required = false) final String policy,
                       @RequestParam(value = "text", required = false) final String text,
                       final Authentication authentication, final HttpServletRequest request, final Model model) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        // The servlet container drops a form's fields past its post size limit (2 MB by default).
        if (policy == null || text == null) {
            model.addAttribute("textError", request.getContentLengthLong() > MAXIMUM_FORM_BYTES
                    ? "The text is larger than a form can send (2 MB). Try a shorter part, or a PDF."
                    : "Choose a policy and enter some text.");
            return page(person, request, model, policy == null ? "default" : policy.trim(), "");
        }
        final String chosen = policy.trim();
        if (chosen.isEmpty() || text.isBlank()) {
            model.addAttribute("textError", chosen.isEmpty() ? "Choose a policy." : "Enter some text to redact.");
            return page(person, request, model, chosen, text);
        }
        try {
            model.addAttribute("redacted", philter.redactText(person.getEncryptedSessionKey(), chosen, text));
            audit(chosen, "text", text.length());
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            model.addAttribute("textError", redactError(e));
        }
        return page(person, request, model, chosen, text);
    }

    /** Redacts the PDF and returns the result as a download; neither is kept. */
    @PostMapping(PATH + "/pdf")
    public ModelAndView pdf(@RequestParam("policy") final String policy,
                            @RequestParam(value = "file", required = false) final MultipartFile file,
                            final Authentication authentication, final HttpServletRequest request,
                            final HttpServletResponse response, final Model model) throws IOException {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return new ModelAndView("redirect:/login");
        }
        final String chosen = policy.trim();
        String problem = chosen.isEmpty() ? "Choose a policy." : null;
        byte[] pdf = null;
        if (problem == null) {
            pdf = file == null ? null : file.getBytes();
            if (pdf == null || pdf.length == 0) {
                problem = "Choose a PDF to redact.";
            } else if (!isPdf(pdf)) {
                problem = "That file is not a PDF.";
            }
        }
        if (problem == null) {
            try {
                final byte[] redacted = philter.redactPdf(person.getEncryptedSessionKey(), chosen, pdf);
                audit(chosen, "pdf", pdf.length);
                response.setContentType(MediaType.APPLICATION_PDF_VALUE);
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + downloadName(file.getOriginalFilename()) + "\"");
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                response.setContentLength(redacted.length);
                response.getOutputStream().write(redacted);
                response.flushBuffer();
                return null;
            } catch (Exception e) {
                if (e instanceof UnauthorizedException) {
                    return new ModelAndView(PhilterSessions.end(request));
                }
                problem = redactError(e);
            }
        }
        model.addAttribute("pdfError", problem);
        return new ModelAndView(page(person, request, model, chosen, ""));
    }

    @GetMapping(MODELS_PATH)
    public String models(final Authentication authentication, final HttpServletRequest request, final Model model) {
        final PhilterSessionAuthentication person = person(authentication);
        if (person == null) {
            return "redirect:/login";
        }
        try {
            model.addAttribute("models", philter.models(person.getEncryptedSessionKey()));
        } catch (Exception e) {
            if (e instanceof UnauthorizedException) {
                return PhilterSessions.end(request);
            }
            log.warn("Could not list Philter's models: {}", PhilterClients.describe(e));
            model.addAttribute("error", FormErrors.from(e).message());
            model.addAttribute("models", List.of());
        }
        model.addAttribute("activeTab", "models");
        return "policy-models";
    }

    // ---- helpers

    private String page(final PhilterSessionAuthentication person, final HttpServletRequest request,
                        final Model model, final String policy, final String text) {
        List<String> policies = List.of();
        try {
            policies = philter.policyNames(person.getEncryptedSessionKey());
        } catch (Exception e) {
            log.warn("Could not list the person's Philter policies: {}", PhilterClients.describe(e));
        }
        model.addAttribute("policies", policies);
        model.addAttribute("policy", policy);
        model.addAttribute("text", text);
        return "try";
    }

    /** Records that a try happened, never what was tried: Philter does not audit a synchronous redaction. */
    private void audit(final String policy, final String kind, final int size) {
        auditLogService.log("POLICY_TRY", "Policy", null, Map.of("policy", policy, "kind", kind,
                kind.equals("pdf") ? "bytes" : "characters", size));
    }

    static String redactError(final Exception e) {
        if (e instanceof PhilterError error) {
            switch (error.getStatusCode()) {
                case 404:
                    return NO_POLICY;
                case 400:
                case 422:
                    return CANNOT_APPLY;
                default:
                    break;
            }
        }
        return FormErrors.from(e).message();
    }

    /** A PDF starts with {@code %PDF-}. */
    static boolean isPdf(final byte[] bytes) {
        return bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F'
                && bytes[4] == '-';
    }

    /** The upload's name with anything unsafe in a filename replaced, marked redacted. */
    public static String downloadName(final String original) {
        String base = original == null ? "" : original.replaceAll("(?i)\\.pdf$", "");
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isBlank() || base.chars().allMatch(c -> c == '_' || c == '.')) {
            base = "document";
        }
        if (base.length() > 100) {
            base = base.substring(0, 100);
        }
        return base + "-redacted.pdf";
    }

    private static PhilterSessionAuthentication person(final Authentication authentication) {
        return authentication instanceof PhilterSessionAuthentication philterSession ? philterSession : null;
    }
}
