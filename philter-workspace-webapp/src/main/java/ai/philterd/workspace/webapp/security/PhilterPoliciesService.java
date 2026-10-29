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

import ai.philterd.philter.model.GetDeletedPoliciesResponse;
import ai.philterd.philter.model.ListOptions;
import ai.philterd.philter.model.Page;
import ai.philterd.philter.model.PolicyDetails;
import ai.philterd.philter.model.PolicyDiff;
import ai.philterd.philter.model.PolicyRollbackResponse;
import ai.philterd.philter.model.PolicySummary;
import ai.philterd.philter.model.PolicyVersionSummary;
import ai.philterd.philter.model.PolicyWithETag;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Philter's Policies API, called with the signed-in person's session key, so Philter's per-user ownership
 * applies and its audit log names them. {@code owner} is another user's username, which Philter allows an
 * administrator only with {@code ADMIN_CROSS_USER_ACCESS_ENABLED=true}, or null for the person's own.
 */
@Service
public class PhilterPoliciesService {

    private final PhilterSignInService signInService;

    public PhilterPoliciesService(final PhilterSignInService signInService) {
        this.signInService = signInService;
    }

    public Page<PolicySummary> list(final String key, final String owner, final ListOptions options) throws IOException {
        return signInService.client(key).listPolicies(owner, options);
    }

    public Page<PolicySummary> listAcrossUsers(final String key, final ListOptions options) throws IOException {
        return signInService.client(key).listPoliciesAcrossUsers(options);
    }

    public Page<PolicySummary> listManaged(final String key, final ListOptions options) throws IOException {
        return signInService.client(key).listManagedPolicies(options);
    }

    public GetDeletedPoliciesResponse listDeleted(final String key, final String owner, final ListOptions options)
            throws IOException {
        return signInService.client(key).getDeletedPolicies(owner, options);
    }

    /** A policy's JSON. A managed policy is read by its {@code managed_} name with no owner. */
    public String get(final String key, final String name, final String owner) throws IOException {
        return signInService.client(key).getPolicy(name, owner);
    }

    public PolicyWithETag getWithETag(final String key, final String name, final String owner) throws IOException {
        return signInService.client(key).getPolicyWithETag(name, owner);
    }

    public PolicyDetails details(final String key, final String name, final String owner) throws IOException {
        return signInService.client(key).getPolicyDetails(name, owner);
    }

    /** Philter's starting point for a new policy. */
    public String template(final String key, final String templateName) throws IOException {
        return signInService.client(key).getPolicyTemplate(templateName);
    }

    /** Creates a policy, with its description and notes, in one request. */
    public void create(final String key, final String name, final String json, final String description,
                       final String notes, final String owner) throws IOException {
        signInService.client(key).savePolicy(name, json, description, notes, owner);
    }

    /**
     * Replaces a policy and, when not null, its description and notes, only if it is still at {@code eTag}.
     * Returns the new entity tag.
     */
    public String replace(final String key, final String name, final String json, final String description,
                          final String notes, final String eTag, final String owner) throws IOException {
        return signInService.client(key).replacePolicy(name, json, description, notes, eTag, owner);
    }

    public void copy(final String key, final String source, final String name, final String owner) throws IOException {
        signInService.client(key).copyPolicy(source, name, owner);
    }

    public void delete(final String key, final String name, final String owner) throws IOException {
        signInService.client(key).deletePolicy(name, owner);
    }

    /** As many versions as Philter returns in one page, newest first. */
    public Page<PolicyVersionSummary> versions(final String key, final String name, final String owner)
            throws IOException {
        return signInService.client(key).listPolicyVersions(name, owner, new ListOptions().offset(0).limit(100));
    }

    public String version(final String key, final String name, final int revision, final String owner)
            throws IOException {
        return signInService.client(key).getPolicyVersion(name, revision, owner);
    }

    public PolicyDiff diff(final String key, final String name, final int from, final int to, final String owner)
            throws IOException {
        return signInService.client(key).diffPolicy(name, from, to, owner);
    }

    /** Makes {@code revision}'s content a new revision, only if the policy is still at {@code eTag}. */
    public PolicyRollbackResponse rollback(final String key, final String name, final int revision, final String eTag,
                                           final String owner) throws IOException {
        return signInService.client(key).rollbackPolicy(name, revision, eTag, owner);
    }

    /** Restores a deleted policy from one of its revisions, under the same name; its history continues. */
    public void restore(final String key, final String name, final int revision, final String owner)
            throws IOException {
        final ai.philterd.philter.PhilterClient client = signInService.client(key);
        client.savePolicy(name, client.getPolicyVersion(name, revision, owner), owner);
    }
}
