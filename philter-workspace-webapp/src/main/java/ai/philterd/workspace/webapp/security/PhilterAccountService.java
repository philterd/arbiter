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

import ai.philterd.philter.model.ApiKey;
import ai.philterd.philter.model.ApiKeyScopeDescription;
import ai.philterd.philter.model.GetApiKeysResponse;
import ai.philterd.philter.model.GetWebhookDeliveriesResponse;
import ai.philterd.philter.model.ListOptions;
import ai.philterd.philter.model.Webhook;
import ai.philterd.philter.model.WebhookTestResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * A person's own Philter account: their MFA, their long-lived API keys, and their webhook, called with their
 * session key. Philter refuses a session key the creation of API keys and any widening of a key's scopes, so
 * neither is offered.
 */
@Service
public class PhilterAccountService {

    private final PhilterSignInService signInService;

    public PhilterAccountService(final PhilterSignInService signInService) {
        this.signInService = signInService;
    }

    /** Removes the person's own MFA; Philter takes a current code, so a stolen session cannot. */
    public void removeMfa(final String key, final String code) throws IOException {
        signInService.client(key).removeMfaEnrollment(code);
    }

    /** The person's long-lived keys, not their sign-in sessions. */
    public GetApiKeysResponse apiKeys(final String key, final ListOptions options) throws IOException {
        return signInService.client(key).getApiKeys(null, options.session(false));
    }

    /** Every scope Philter has, with what each allows. */
    public List<ApiKeyScopeDescription> scopes(final String key) throws IOException {
        final List<ApiKeyScopeDescription> scopes = signInService.client(key).listApiKeyScopes().getScopes();
        return scopes == null ? List.of() : scopes;
    }

    public ApiKey setScopes(final String key, final String keyId, final List<String> scopes) throws IOException {
        return signInService.client(key).setApiKeyScopes(keyId, scopes);
    }

    public void revoke(final String key, final String keyId) throws IOException {
        signInService.client(key).revokeApiKey(keyId);
    }

    public Webhook webhook(final String key) throws IOException {
        return signInService.client(key).getWebhook();
    }

    /** Sets the webhook; a null secret keeps the one already set. */
    public Webhook setWebhook(final String key, final String url, final String secret) throws IOException {
        return signInService.client(key).setWebhook(url, secret);
    }

    public void removeWebhook(final String key) throws IOException {
        signInService.client(key).removeWebhook();
    }

    public WebhookTestResult testWebhook(final String key) throws IOException {
        return signInService.client(key).testWebhook();
    }

    public GetWebhookDeliveriesResponse deliveries(final String key, final ListOptions options) throws IOException {
        return signInService.client(key).getWebhookDeliveries(null, options);
    }
}
