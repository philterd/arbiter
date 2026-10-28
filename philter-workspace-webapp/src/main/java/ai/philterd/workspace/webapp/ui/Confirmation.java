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

import java.util.List;
import java.util.Map;

/**
 * A destructive action to confirm, such as deleting, releasing, or revoking. The {@code fragments/confirm}
 * fragment shows it in a dialog; with JavaScript off, its link opens {@code pageUrl} instead, whose handler
 * renders the {@code confirm} template with this as {@code confirmation}. Build it in one place and use it
 * for both, so the dialog and the page say the same thing.
 *
 * @param title    what is about to happen, such as "Deactivate jdoe?"
 * @param message  its consequences
 * @param affected the items it affects, named, or empty
 * @param action   where the confirmed form posts
 * @param button   the confirming button's label, such as "Deactivate"
 * @param pageUrl  the confirmation page, for browsers without JavaScript
 * @param backUrl  where Cancel goes on that page
 * @param fields   hidden fields posted with the form
 */
public record Confirmation(String title, String message, List<String> affected, String action, String button,
                           String pageUrl, String backUrl, Map<String, String> fields) {

    public Confirmation {
        affected = affected == null ? List.of() : List.copyOf(affected);
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    public Confirmation(final String title, final String message, final String action, final String button,
                        final String pageUrl, final String backUrl) {
        this(title, message, List.of(), action, button, pageUrl, backUrl, Map.of());
    }
}
