/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.ui;

import org.springframework.security.core.Authentication;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whose items a list shows: the signed-in person's own (the default), one user's ({@code owner=} in
 * Philter), or every user's ({@code all_users=true} in Philter, the SDK's {@code ...AcrossUsers} calls).
 * Philter lets only its administrators choose, so for anyone else the request's {@code owner} and
 * {@code all} parameters are ignored and {@link #available()} is false, which hides the control.
 */
public record OwnerFilter(boolean available, String username, boolean everyone) {

    private static final OwnerFilter NONE = new OwnerFilter(false, null, false);

    /** No owner control: the list always shows the person's own items. */
    public static OwnerFilter none() {
        return NONE;
    }

    /**
     * The filter for this request: {@code all=true} for everyone's items, else {@code owner} for one
     * user's, else the person's own, when they are an administrator.
     */
    public static OwnerFilter from(final Authentication authentication, final String owner, final String all) {
        if (!isAdmin(authentication)) {
            return NONE;
        }
        if ("true".equalsIgnoreCase(ListView.text(all))) {
            return new OwnerFilter(true, null, true);
        }
        return new OwnerFilter(true, ListView.text(owner), false);
    }

    /** True when the list shows the person's own items. */
    public boolean mine() {
        return !everyone && username == null;
    }

    /** The request parameters that keep this filter on links. */
    public Map<String, String> params() {
        final Map<String, String> params = new LinkedHashMap<>();
        if (everyone) {
            params.put("all", "true");
        } else if (username != null) {
            params.put("owner", username);
        }
        return params;
    }

    private static boolean isAdmin(final Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
