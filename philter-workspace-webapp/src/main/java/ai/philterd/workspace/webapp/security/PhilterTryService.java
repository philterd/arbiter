/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.security;

import ai.philterd.philter.model.ListOptions;
import ai.philterd.philter.model.Model;
import ai.philterd.philter.model.PolicySummary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Redacts text or a PDF with one of the person's policies, and lists Philter's models, with the signed-in person's
 * session key. No context is sent, so Philter keeps no replacement for later requests and writes no ledger entry,
 * and nothing is stored in Philter Workspace.
 */
@Service
public class PhilterTryService {

    /** The most policy names offered to choose from; any other can still be typed. */
    static final int POLICY_CHOICES = 100;

    private final PhilterSignInService signInService;

    public PhilterTryService(final PhilterSignInService signInService) {
        this.signInService = signInService;
    }

    /** The person's policies, by name, up to {@link #POLICY_CHOICES}. */
    public List<String> policyNames(final String key) throws IOException {
        return signInService.client(key).listPolicies(null, new ListOptions().offset(0).limit(POLICY_CHOICES)
                .sort("name").ascending()).getItems().stream().map(PolicySummary::getName).toList();
    }

    public String redactText(final String key, final String policy, final String text) throws IOException {
        return signInService.client(key).filter(null, policy, text).getFilteredText();
    }

    /**
     * The redacted PDF Philter returns. No filename is sent: Philter would only record it against a ledger entry,
     * which this request does not write, and a filename can name a person.
     */
    public byte[] redactPdf(final String key, final String policy, final byte[] pdf) throws IOException {
        return signInService.client(key).filterToPdf(null, policy, null, pdf).getContent();
    }

    public List<Model> models(final String key) throws IOException {
        final List<Model> models = signInService.client(key).getModels();
        return models == null ? List.of() : models;
    }
}
